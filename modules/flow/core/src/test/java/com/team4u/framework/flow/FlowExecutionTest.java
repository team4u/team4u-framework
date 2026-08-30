package com.team4u.framework.flow;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 流程核心执行行为测试。
 *
 * @author jay.wu
 */
public class FlowExecutionTest {

    @Test
    public void typedSequence_success() {
        Flow<Integer, String> flow = Flows.<Integer>begin("math-flow")
                .step("double", in -> in * 2)
                .step("add-one", in -> in + 1)
                .step("to-string", in -> "result=" + in)
                .build();

        String result = flow.call(5);
        Assert.assertEquals("result=11", result);

        FlowExecution<String> exec = flow.run(5, RunOptions.builder().trace(true).build());
        Assert.assertTrue(exec.result().isSucceeded());
        Assert.assertEquals("result=11", exec.result().value());
        Assert.assertEquals(3, exec.trace().entries().size());
        Assert.assertEquals("double", exec.trace().entries().get(0).nodeId());
        Assert.assertEquals("add-one", exec.trace().entries().get(1).nodeId());
        Assert.assertEquals("to-string", exec.trace().entries().get(2).nodeId());
    }

    @Test
    public void tapPassThrough_contextUpdated() {
        class Context {
            int counter = 0;
        }

        Flow<Context, Context> flow = Flows.<Context>begin("tap-flow")
                .tap("inc1", c -> c.counter += 10)
                .tap("inc2", c -> c.counter += 5)
                .build();

        Context ctx = new Context();
        Context out = flow.call(ctx);
        Assert.assertSame(ctx, out);
        Assert.assertEquals(15, ctx.counter);
    }

    @Test
    public void contextualStep_receivesExecutionIdAndInvocationId() {
        List<String> recordedExecIds = new ArrayList<>();
        List<String> recordedInvocIds = new ArrayList<>();

        Flow<String, String> flow = Flows.<String>begin("ctx-flow")
                .step("s1", (ctx, in) -> {
                    recordedExecIds.add(ctx.executionId());
                    recordedInvocIds.add(ctx.invocationId());
                    return in + "-1";
                })
                .step("s2", (ctx, in) -> {
                    recordedExecIds.add(ctx.executionId());
                    recordedInvocIds.add(ctx.invocationId());
                    return in + "-2";
                })
                .build();

        FlowExecution<String> exec = flow.run("val", RunOptions.builder().executionId("exec-99").build());
        Assert.assertEquals("val-1-2", exec.result().value());
        Assert.assertEquals(2, recordedExecIds.size());
        Assert.assertEquals("exec-99", recordedExecIds.get(0));
        Assert.assertEquals("exec-99", recordedExecIds.get(1));

        Assert.assertNotNull(recordedInvocIds.get(0));
        Assert.assertNotNull(recordedInvocIds.get(1));
        Assert.assertNotEquals(recordedInvocIds.get(0), recordedInvocIds.get(1));
        Assert.assertTrue(recordedInvocIds.get(0).startsWith("exec-99#"));
        Assert.assertTrue(recordedInvocIds.get(1).startsWith("exec-99#"));
    }

    @Test
    public void lazyExecutionId_consistentAcrossNodes() {
        List<String> recordedExecIds = new ArrayList<>();

        Flow<String, String> flow = Flows.<String>begin("lazy-flow")
                .step("s1", (ctx, in) -> {
                    recordedExecIds.add(ctx.executionId());
                    return in;
                })
                .step("s2", (ctx, in) -> {
                    recordedExecIds.add(ctx.executionId());
                    return in;
                })
                .build();

        // Run without specifying executionId
        FlowExecution<String> exec = flow.run("val");
        Assert.assertEquals(2, recordedExecIds.size());
        Assert.assertNotNull(recordedExecIds.get(0));
        Assert.assertEquals(recordedExecIds.get(0), recordedExecIds.get(1));
        Assert.assertEquals(recordedExecIds.get(0), exec.executionId());
    }

    @Test
    public void guard_passed_continuesExecution() {
        Flow<Integer, String> flow = Flows.<Integer>begin("guard-pass")
                .guard("check-positive", in -> in > 0, in -> StopReason.of("NON_POSITIVE"))
                .step("convert", in -> "pos=" + in)
                .build();

        String res = flow.call(10);
        Assert.assertEquals("pos=10", res);
    }

    @Test
    public void guard_failed_stopsExecution() {
        Flow<Integer, String> flow = Flows.<Integer>begin("guard-stop")
                .guard("check-positive", in -> in > 0, in -> StopReason.of("NON_POSITIVE", "Value must be positive: " + in))
                .step("convert", in -> "pos=" + in)
                .build();

        FlowExecution<String> exec = flow.run(-5, RunOptions.builder().trace(true).build());
        Assert.assertTrue(exec.result().isStopped());
        Assert.assertEquals("NON_POSITIVE", exec.result().stopReason().code());
        Assert.assertEquals("Value must be positive: -5", exec.result().stopReason().message());
        Assert.assertEquals(1, exec.trace().entries().size());
        Assert.assertEquals(FlowResult.Kind.STOPPED, exec.trace().entries().get(0).status());

        try {
            flow.call(-5);
            Assert.fail("Expected FlowRunException");
        } catch (FlowRunException e) {
            Assert.assertTrue(e.result().isStopped());
            Assert.assertEquals("NON_POSITIVE", e.stopReason().code());
        }
    }

    @Test
    public void choose_branchHit_executesBranchOnly() {
        Flow<String, String> cardFlow = Flows.step("card-step", in -> in + ":CARD");
        Flow<String, String> walletFlow = Flows.step("wallet-step", in -> in + ":WALLET");

        Flow<String, String> flow = Flows.<String>begin("choose-flow")
                .choose("select-channel", in -> in.split(":")[0])
                .when("CARD", cardFlow)
                .when("WALLET", walletFlow)
                .end()
                .build();

        FlowExecution<String> exec = flow.run("CARD:order1", RunOptions.builder().trace(true).build());
        Assert.assertTrue(exec.result().isSucceeded());
        Assert.assertEquals("CARD:order1:CARD", exec.result().value());
        Assert.assertEquals(1, exec.trace().entries().size());
        Assert.assertEquals("CARD", exec.trace().entries().get(0).branchKey());
    }

    @Test
    public void choose_unmatched_withoutOtherwise_fails() {
        Flow<String, String> cardFlow = Flows.step("card-step", in -> in + ":CARD");

        Flow<String, String> flow = Flows.<String>begin("choose-fail")
                .choose("select-channel", in -> in.split(":")[0])
                .when("CARD", cardFlow)
                .end()
                .build();

        FlowExecution<String> exec = flow.run("UNKNOWN:order", RunOptions.builder().trace(true).build());
        Assert.assertTrue(exec.result().isFailed());
        Assert.assertEquals("select-channel", exec.result().failure().nodeId());
    }

    @Test
    public void choose_otherwise_and_otherwiseStop() {
        Flow<String, String> cardFlow = Flows.step("card-step", in -> in + ":CARD");
        Flow<String, String> otherFlow = Flows.step("other-step", in -> in + ":OTHER");

        Flow<String, String> flowWithOtherwise = Flows.<String>begin("choose-other")
                .choose("select", in -> in)
                .when("CARD", cardFlow)
                .otherwise(otherFlow)
                .end()
                .build();

        Assert.assertEquals("CASH:OTHER", flowWithOtherwise.call("CASH"));

        Flow<String, String> flowWithStop = Flows.<String>begin("choose-stop")
                .choose("select", in -> in)
                .when("CARD", cardFlow)
                .otherwiseStop(in -> StopReason.of("UNSUPPORTED_CHANNEL", in))
                .end()
                .build();

        FlowExecution<String> stopExec = flowWithStop.run("CASH");
        Assert.assertTrue(stopExec.result().isStopped());
        Assert.assertEquals("UNSUPPORTED_CHANNEL", stopExec.result().stopReason().code());
    }

    @Test
    public void subflow_nested_preservesHierarchy() {
        Flow<Integer, Integer> childFlow = Flows.<Integer>begin("child")
                .step("child-step1", in -> in * 2)
                .step("child-step2", in -> in + 3)
                .build();

        Flow<Integer, String> parentFlow = Flows.<Integer>begin("parent")
                .step("parent-prep", in -> in + 10)
                .then(childFlow)
                .step("parent-finalize", in -> "final=" + in)
                .build();

        FlowExecution<String> exec = parentFlow.run(5, RunOptions.builder().trace(true).build());
        Assert.assertTrue(exec.result().isSucceeded());
        // 5 + 10 = 15 -> (15 * 2) + 3 = 33 -> "final=33"
        Assert.assertEquals("final=33", exec.result().value());

        List<FlowTrace.Entry> entries = exec.trace().entries();
        Assert.assertEquals(3, entries.size());
        Assert.assertEquals("parent-prep", entries.get(0).nodeId());
        Assert.assertEquals("child", entries.get(1).nodeId());
        Assert.assertEquals("parent-finalize", entries.get(2).nodeId());

        List<FlowTrace.Entry> childEntries = entries.get(1).children();
        Assert.assertEquals(2, childEntries.size());
        Assert.assertEquals("child-step1", childEntries.get(0).nodeId());
        Assert.assertEquals("child-step2", childEntries.get(1).nodeId());
    }

    @Test
    public void stepFailure_preservesCause() {
        IllegalArgumentException expectedEx = new IllegalArgumentException("Invalid amount");

        Flow<Integer, Integer> flow = Flows.<Integer>begin("fail-flow")
                .step("validate", in -> {
                    if (in < 0) throw expectedEx;
                    return in;
                })
                .build();

        FlowExecution<Integer> exec = flow.run(-1);
        Assert.assertTrue(exec.result().isFailed());
        Assert.assertEquals("validate", exec.result().failure().nodeId());
        Assert.assertSame(expectedEx, exec.result().failure().cause());

        try {
            flow.call(-1);
            Assert.fail("Expected FlowRunException");
        } catch (FlowRunException e) {
            Assert.assertTrue(e.result().isFailed());
            Assert.assertSame(expectedEx, e.getCause());
        }
    }

    @Test
    public void observerFailure_isolatedAndDoesNotAffectResult() {
        AtomicInteger eventCount = new AtomicInteger();
        FlowObserver brokenObserver = event -> {
            eventCount.incrementAndGet();
            throw new RuntimeException("Observer error");
        };

        Flow<String, String> flow = Flows.<String>begin("observer-test")
                .step("step1", in -> in + "-ok")
                .build();

        FlowExecution<String> exec = flow.run("test", RunOptions.builder().observer(brokenObserver).build());
        Assert.assertTrue(exec.result().isSucceeded());
        Assert.assertEquals("test-ok", exec.result().value());
        Assert.assertTrue(eventCount.get() > 0);
    }

    @Test
    public void interceptorOrder_beforeForward_afterBackward() {
        List<String> order = new ArrayList<>();

        StepInterceptor i1 = new StepInterceptor() {
            @Override
            public <I, O> O intercept(Chain<I, O> chain) throws Exception {
                order.add("i1-before");
                O out = chain.proceed(chain.input());
                order.add("i1-after");
                return out;
            }
        };

        StepInterceptor i2 = new StepInterceptor() {
            @Override
            public <I, O> O intercept(Chain<I, O> chain) throws Exception {
                order.add("i2-before");
                O out = chain.proceed(chain.input());
                order.add("i2-after");
                return out;
            }
        };

        Flow<String, String> flow = Flows.<String>begin("intercept-flow")
                .interceptor(i1)
                .interceptor(i2)
                .step("s1", in -> {
                    order.add("step-executed");
                    return in + "-done";
                })
                .build();

        String result = flow.call("in");
        Assert.assertEquals("in-done", result);
        Assert.assertEquals(Arrays.asList("i1-before", "i2-before", "step-executed", "i2-after", "i1-after"), order);
    }

    @Test
    public void concurrentExecution_isolatedAndThreadSafe() throws Exception {
        Flow<Integer, Integer> flow = Flows.<Integer>begin("concurrent-flow")
                .step("calc", in -> in * 10)
                .build();

        int threads = 10;
        int runsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            futures.add(executor.submit(() -> {
                startLatch.await();
                for (int i = 0; i < runsPerThread; i++) {
                    int val = threadId * 1000 + i;
                    FlowExecution<Integer> exec = flow.run(val, RunOptions.builder()
                            .executionId("exec-" + threadId + "-" + i)
                            .trace(true)
                            .build());
                    if (!exec.result().isSucceeded() || exec.result().value() != val * 10) {
                        return false;
                    }
                    if (!("exec-" + threadId + "-" + i).equals(exec.executionId())) {
                        return false;
                    }
                }
                return true;
            }));
        }

        startLatch.countDown();
        for (Future<Boolean> f : futures) {
            Assert.assertTrue(f.get(10, TimeUnit.SECONDS));
        }
        executor.shutdown();
    }
}
