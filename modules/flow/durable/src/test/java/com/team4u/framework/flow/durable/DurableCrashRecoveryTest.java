package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.Flow;
import org.junit.Test;

import static com.team4u.framework.flow.durable.DurableTestOps.RecordingOp;
import static com.team4u.framework.flow.durable.DurableTestOps.SimulatedCrash;
import static com.team4u.framework.flow.durable.DurableTestOps.acceptedValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.api.Retry;
import com.team4u.framework.flow.durable.store.DurableStore;
import com.team4u.framework.flow.durable.store.InMemoryDurableStore;
import com.team4u.framework.flow.model.Recovery;
import com.team4u.framework.flow.model.Failure;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Reason;

/** 组3：crash/recover — 已 checkpoint 的 invoke 不重放、未 checkpoint 重放且 invocationId 稳定、深嵌套恢复。 */
public class DurableCrashRecoveryTest {

    private static DurableExecutable<String, String> compile(Flow<String, String> flow,
                                                             DurableStore store) {
        return DurableRuntime.builder(store).build().compile(flow, "crash", 1);
    }

    /** 驱动直到完成；SimulatedCrash 视为进程崩溃，返回崩溃与否。 */
    private static boolean driveUntilDone(DurableExecutable<String, String> executable,
                                          String executionId, int maxRecoveries) {
        try {
            executable.start(executionId, "in");
            return false;
        } catch (SimulatedCrash crash) {
            return recoverUntilDone(executable, executionId, maxRecoveries);
        }
    }

    private static boolean recoverUntilDone(DurableExecutable<String, String> executable,
                                            String executionId, int maxRecoveries) {
        for (int i = 0; i < maxRecoveries; i++) {
            try {
                DurableResult<String> result = executable.recover(executionId);
                assertTrue(result instanceof DurableResult.Completed
                        || result instanceof DurableResult.Suspended
                        || result instanceof DurableResult.Active);
                return false;
            } catch (SimulatedCrash crash) {
                // 继续恢复
            }
        }
        return true;
    }

    @Test
    public void checkpointedInvokeIsNotReplayedAfterCrash() {
        RecordingOp a = new RecordingOp("a");
        RecordingOp b = new RecordingOp("b").crashOnCall(1);
        RecordingOp c = new RecordingOp("c");
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable =
                compile(Flow.<String, String>step(a).then(b).then(c), store);
        try {
            executable.start("e", "in");
            fail("b 首次调用应崩溃");
        } catch (SimulatedCrash expected) {
            // 崩溃：a 已 checkpoint
        }
        DurableResult<String> result = executable.recover("e");
        assertTrue(result instanceof DurableResult.Completed);
        assertEquals("a 已 checkpoint 不重放", 1, a.calls());
        assertEquals("b 崩溃后重放", 2, b.calls());
        assertEquals(1, c.calls());
        assertEquals("in>a>b>c", acceptedValue(result));
    }

    @Test
    public void uncheckedInvokeReplayKeepsStableInvocationId() {
        RecordingOp a = new RecordingOp("a");
        RecordingOp b = new RecordingOp("b").crashOnCall(1);
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable =
                compile(Flow.<String, String>step(a).then(b), store);
        try {
            executable.start("e", "in");
            fail();
        } catch (SimulatedCrash expected) {
            assertEquals(1, b.invocations().size());
        }
        executable.recover("e");
        assertEquals("崩溃前后 invocationId 必须一致",
                b.invocations().get(0), b.invocations().get(1));
        assertTrue(b.invocations().get(0).endsWith(":$/1"));
    }

    @Test
    public void recoverOnMissingExecutionFails() {
        RecordingOp a = new RecordingOp("a");
        DurableExecutable<String, String> executable =
                compile(Flow.<String, String>step(a), new InMemoryDurableStore());
        try {
            executable.recover("ghost");
            fail();
        } catch (DurableException error) {
            assertEquals(DurableException.Error.EXECUTION_NOT_FOUND, error.error());
        }
    }

    @Test
    public void crashInsideFallbackBranchRecovers() {
        RecordingOp body = new RecordingOp("body").returns(
                Outcome.failed(Failure.of("ERR", "err")));
        final java.util.concurrent.atomic.AtomicInteger recCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        com.team4u.framework.flow.api.Operation<com.team4u.framework.flow.model.Recovery<String>, String>
                recoverOp = new com.team4u.framework.flow.api.Operation<com.team4u.framework.flow.model.Recovery<String>, String>() {
            @Override
            public Outcome<String> execute(
                    com.team4u.framework.flow.api.OperationContext ctx,
                    com.team4u.framework.flow.model.Recovery<String> recovery) {
                if (recCalls.incrementAndGet() == 1) {
                    throw new SimulatedCrash("crash in fallback");
                }
                return Outcome.accepted(recovery.input() + ">rec");
            }
        };
        Flow<com.team4u.framework.flow.model.Recovery<String>, String> recover =
                Flow.step(recoverOp);
        Flow<String, String> flow = Flow.<String, String>step(body).recoverWith(recover);
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable = compile(flow, store);
        try {
            executable.start("e", "in");
        } catch (SimulatedCrash expected) {
            // fallback 分支崩溃
        }
        DurableResult<String> result = executable.recover("e");
        assertTrue(result instanceof DurableResult.Completed);
        assertEquals("in>rec", acceptedValue(result));
        assertEquals(2, recCalls.get());
    }

    @Test
    public void deepNestedFramesRecoverExactlyOnce() {
        // 嵌套 ≥ 20 帧：scope(sequence) > route(case) > fallback > retry > invoke 链
        RecordingOp deep = new RecordingOp("deep").crashOnCall(1);
        Flow<String, String> leaf = Flow.<String, String>step(deep)
                .retry(new com.team4u.framework.flow.api.Retry(2,
                        java.time.Duration.ofMillis(1)));
        Flow<String, String> branch = Flow.firstApplicable(
                Flow.<String, String>skipped(Reason.of("NA", "na")),
                leaf);
        Flow<String, String> routed = Flow.<String, String>route(
                new com.team4u.framework.flow.api.Operation<String, String>() {
                    @Override
                    public Outcome<String> execute(
                            com.team4u.framework.flow.api.OperationContext ctx, String input) {
                        return Outcome.accepted("go");
                    }
                }).caseOf("go", branch).otherwise(Flow.<String>identity());
        Flow<String, String> flow = Flow.scope("outer", routed);
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable = compile(flow, store);
        try {
            executable.start("e", "in");
        } catch (SimulatedCrash expected) {
            // 深层 invoke 崩溃
        }
        DurableResult<String> result = executable.recover("e");
        assertTrue(result instanceof DurableResult.Completed);
        assertEquals(2, deep.calls());
        assertEquals("in>deep", acceptedValue(result));
    }

    @Test
    public void repeatedCrashesAllRecover() {
        RecordingOp a = new RecordingOp("a");
        RecordingOp b = new RecordingOp("b").crashOnCall(1).crashOnCall(2);
        // crashOnCall 只支持一次，改用手动计数崩溃
        final java.util.concurrent.atomic.AtomicInteger crashes = new java.util.concurrent.atomic.AtomicInteger();
        com.team4u.framework.flow.api.Operation<String, String> flaky =
                new com.team4u.framework.flow.api.Operation<String, String>() {
                    @Override
                    public Outcome<String> execute(
                            com.team4u.framework.flow.api.OperationContext ctx, String input) {
                        if (crashes.incrementAndGet() <= 2) {
                            throw new SimulatedCrash("crash #" + crashes.get());
                        }
                        return Outcome.accepted(input + "-ok");
                    }
                };
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable =
                compile(Flow.<String, String>step(a).then(Flow.step(flaky)), store);
        try {
            executable.start("e", "in");
            fail();
        } catch (SimulatedCrash expected) {
            // 第一次崩溃
        }
        try {
            executable.recover("e");
            fail();
        } catch (SimulatedCrash expected) {
            // 第二次崩溃
        }
        DurableResult<String> result = executable.recover("e");
        assertEquals("in>a-ok", acceptedValue(result));
        assertEquals("a 只执行一次", 1, a.calls());
    }

    @Test
    public void recoverAfterCompletionIsLifecycleMismatch() {
        RecordingOp a = new RecordingOp("a");
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable =
                compile(Flow.<String, String>step(a), store);
        executable.start("e", "in");
        try {
            executable.recover("e");
            fail("COMPLETED 上的 recover 必须 LIFECYCLE_MISMATCH");
        } catch (DurableException error) {
            assertEquals(DurableException.Error.LIFECYCLE_MISMATCH, error.error());
        }
    }

    @Test
    public void recoveryWithDivergentPlanTopologyIsRejected() {
        RecordingOp a = new RecordingOp("a");
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableRuntime runtime = DurableRuntime.builder(store).build();
        // 使执行在 $/1 前崩溃：路径 $/1 处第一个计划是 invoke，第二个计划是 route
        final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        com.team4u.framework.flow.api.Operation<String, String> crashAfterA =
                new com.team4u.framework.flow.api.Operation<String, String>() {
                    @Override
                    public Outcome<String> execute(
                            com.team4u.framework.flow.api.OperationContext ctx, String input) {
                        if (calls.incrementAndGet() == 1) {
                            throw new SimulatedCrash("die before checkpoint");
                        }
                        return Outcome.accepted(input);
                    }
                };
        // 计划 A：$/0=invoke(a), $/1=invoke(crashAfterA)
        DurableExecutable<String, String> planA = runtime.compile(
                Flow.<String, String>step(a).then(Flow.step(crashAfterA)), "crash", 1);
        try {
            planA.start("e", "in");
        } catch (SimulatedCrash expected) {
            // 在 $/1 崩溃
        }
        // 计划 B：$/1 换成 route —— 同一路径上节点类型不同
        DurableExecutable<String, String> planB = runtime.compile(
                Flow.<String, String>step(a).then(
                        Flow.<String, String>route(
                                new com.team4u.framework.flow.api.Operation<String, String>() {
                                    @Override
                                    public Outcome<String> execute(
                                            com.team4u.framework.flow.api.OperationContext ctx,
                                            String input) {
                                        return Outcome.accepted("x");
                                    }
                                }).caseOf("x", Flow.<String>identity())
                                .withoutOtherwise()),
                "crash", 1);
        try {
            planB.recover("e");
            fail("路径上节点类型不一致必须 FRAME_MISMATCH");
        } catch (DurableException error) {
            assertEquals(DurableException.Error.FRAME_MISMATCH, error.error());
        }
    }
}
