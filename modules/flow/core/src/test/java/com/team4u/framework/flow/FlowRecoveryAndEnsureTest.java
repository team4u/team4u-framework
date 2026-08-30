package com.team4u.framework.flow;

import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 流程恢复（recover）与终态清理（ensure）及异常合并行为测试。
 *
 * @author jay.wu
 */
public class FlowRecoveryAndEnsureTest {

    @Test
    public void recover_success_convertsToSucceeded() {
        Flow<String, String> flow = Flows.<String>begin("rec-flow")
                .step("step-fail", (Step<String, String>) in -> {
                    throw new RuntimeException("DB error");
                })
                .recover("fallback", (in, failure) -> {
                    Assert.assertEquals("step-fail", failure.nodeId());
                    Assert.assertEquals("DB error", failure.cause().getMessage());
                    return FlowResult.succeeded("recovered:" + in);
                })
                .build();

        FlowExecution<String> exec = flow.run("input1", RunOptions.builder().trace(true).build());
        Assert.assertTrue(exec.result().isSucceeded());
        Assert.assertEquals("recovered:input1", exec.result().value());

        // Trace contains both failed step and recover node
        Assert.assertEquals(2, exec.trace().entries().size());
        Assert.assertEquals("step-fail", exec.trace().entries().get(0).nodeId());
        Assert.assertEquals(FlowResult.Kind.FAILED, exec.trace().entries().get(0).status());
        Assert.assertEquals("fallback", exec.trace().entries().get(1).nodeId());
        Assert.assertEquals(FlowResult.Kind.SUCCEEDED, exec.trace().entries().get(1).status());
    }

    @Test
    public void recover_contextual_getsInvocationId() {
        AtomicReference<String> recoverInvocId = new AtomicReference<>();

        Flow<String, String> flow = Flows.<String>begin("rec-ctx-flow")
                .step("fail-node", (Step<String, String>) in -> {
                    throw new RuntimeException("Payment timeout");
                })
                .recover("compensate", (ctx, in, failure) -> {
                    recoverInvocId.set(ctx.invocationId());
                    return FlowResult.succeeded("compensated");
                })
                .build();

        FlowExecution<String> exec = flow.run("order-1", RunOptions.builder().executionId("exec-10").build());
        Assert.assertTrue(exec.result().isSucceeded());
        Assert.assertNotNull(recoverInvocId.get());
        Assert.assertTrue(recoverInvocId.get().startsWith("exec-10#/recover:compensate"));
    }

    @Test
    public void recover_failure_mergesExceptions() {
        RuntimeException originalEx = new RuntimeException("Primary error");
        RuntimeException recoveryEx = new RuntimeException("Recovery error");

        Flow<String, String> flow = Flows.<String>begin("rec-fail")
                .step("primary-step", (Step<String, String>) in -> {
                    throw originalEx;
                })
                .recover("rec-node", (in, failure) -> {
                    throw recoveryEx;
                })
                .build();

        FlowExecution<String> exec = flow.run("in");
        Assert.assertTrue(exec.result().isFailed());
        Assert.assertEquals("rec-node", exec.result().failure().nodeId());
        Assert.assertSame(recoveryEx, exec.result().failure().cause());

        Throwable[] suppressed = recoveryEx.getSuppressed();
        Assert.assertEquals(1, suppressed.length);
        Assert.assertSame(originalEx, suppressed[0]);
    }

    @Test
    public void ensure_executedOnSuccess_readsOutput() {
        AtomicBoolean ensureRan = new AtomicBoolean();
        AtomicReference<String> finalVal = new AtomicReference<>();

        Flow<Integer, String> flow = Flows.<Integer>begin("ensure-success")
                .step("convert", in -> "val=" + in)
                .ensure("cleanup", (in, completion) -> {
                    ensureRan.set(true);
                    Assert.assertTrue(completion.isSucceeded());
                    finalVal.set(completion.value());
                })
                .build();

        String res = flow.call(42);
        Assert.assertEquals("val=42", res);
        Assert.assertTrue(ensureRan.get());
        Assert.assertEquals("val=42", finalVal.get());
    }

    @Test
    public void ensure_executedOnStop_readsStopReason() {
        AtomicBoolean ensureRan = new AtomicBoolean();
        AtomicReference<String> stopCode = new AtomicReference<>();

        Flow<Integer, String> flow = Flows.<Integer>begin("ensure-stop")
                .guard("check", in -> in > 0, in -> StopReason.of("NON_POSITIVE"))
                .step("convert", in -> "val=" + in)
                .ensure("cleanup", (in, completion) -> {
                    ensureRan.set(true);
                    Assert.assertTrue(completion.isStopped());
                    stopCode.set(completion.stopReason().code());
                })
                .build();

        FlowExecution<String> exec = flow.run(-1);
        Assert.assertTrue(exec.result().isStopped());
        Assert.assertTrue(ensureRan.get());
        Assert.assertEquals("NON_POSITIVE", stopCode.get());
    }

    @Test
    public void ensure_executedOnFailure_readsFailure() {
        AtomicBoolean ensureRan = new AtomicBoolean();
        RuntimeException originalEx = new RuntimeException("Boom");

        Flow<String, String> flow = Flows.<String>begin("ensure-fail")
                .step("step1", (Step<String, String>) in -> {
                    throw originalEx;
                })
                .ensure("cleanup", (in, completion) -> {
                    ensureRan.set(true);
                    Assert.assertTrue(completion.isFailed());
                    Assert.assertSame(originalEx, completion.failure().cause());
                })
                .build();

        FlowExecution<String> exec = flow.run("in");
        Assert.assertTrue(exec.result().isFailed());
        Assert.assertTrue(ensureRan.get());
    }

    @Test
    public void ensure_failureOnSuccess_convertsToFailed() {
        RuntimeException cleanupEx = new RuntimeException("Cleanup failed");

        Flow<String, String> flow = Flows.<String>begin("ensure-err-success")
                .step("s1", in -> in + "-ok")
                .ensure("cleanup", (in, completion) -> {
                    throw cleanupEx;
                })
                .build();

        FlowExecution<String> exec = flow.run("in");
        Assert.assertTrue(exec.result().isFailed());
        Assert.assertEquals("cleanup", exec.result().failure().nodeId());
        Assert.assertSame(cleanupEx, exec.result().failure().cause());
    }

    @Test
    public void ensure_failureOnStopped_convertsToFailed() {
        RuntimeException cleanupEx = new RuntimeException("Cleanup failed");

        Flow<Integer, Integer> flow = Flows.<Integer>begin("ensure-err-stop")
                .guard("guard1", in -> false, in -> StopReason.of("STOP_ME"))
                .ensure("cleanup", (in, completion) -> {
                    throw cleanupEx;
                })
                .build();

        FlowExecution<Integer> exec = flow.run(10);
        Assert.assertTrue(exec.result().isFailed());
        Assert.assertEquals("cleanup", exec.result().failure().nodeId());
        Assert.assertSame(cleanupEx, exec.result().failure().cause());
    }

    @Test
    public void ensure_failureOnFailed_originalCauseIsPrimary_ensureAddedToSuppressed() {
        RuntimeException origEx = new RuntimeException("Original error");
        RuntimeException cleanupEx = new RuntimeException("Cleanup error");

        Flow<String, String> flow = Flows.<String>begin("ensure-err-fail")
                .step("s1", (Step<String, String>) in -> {
                    throw origEx;
                })
                .ensure("cleanup", (in, completion) -> {
                    throw cleanupEx;
                })
                .build();

        FlowExecution<String> exec = flow.run("in");
        Assert.assertTrue(exec.result().isFailed());
        Assert.assertEquals("s1", exec.result().failure().nodeId());
        Assert.assertSame(origEx, exec.result().failure().cause());

        Throwable[] suppressed = origEx.getSuppressed();
        Assert.assertEquals(1, suppressed.length);
        Assert.assertSame(cleanupEx, suppressed[0]);
    }
}
