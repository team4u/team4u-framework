package com.team4u.framework.flow.test;

import com.team4u.framework.flow.api.Branch;
import com.team4u.framework.flow.model.Cancellation;
import com.team4u.framework.flow.model.Failure;
import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.api.FlowObserver;
import com.team4u.framework.flow.model.FlowResult;
import com.team4u.framework.flow.api.Gate;
import com.team4u.framework.flow.Local;
import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.spi.OperationResolver;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Reason;
import com.team4u.framework.flow.api.ResumePoint;
import com.team4u.framework.flow.model.Resumed;
import com.team4u.framework.flow.model.Suspension;
import com.team4u.framework.flow.durable.DurableLifecycle;
import com.team4u.framework.flow.durable.DurableResult;
import com.team4u.framework.flow.durable.snapshot.DurableSnapshot;
import com.team4u.framework.flow.durable.snapshot.StoredValue;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * team4u-flow-test 冒烟测试：stub 四态与调用记录、TraceCollector、
 * FlowAssertions（含失败消息可读性）、LocalFixture、DurableFixture 与 ParallelBarrier。
 */
public class TestkitSmokeTest {

    // ------------------------------------------------------------------
    // OperationStub
    // ------------------------------------------------------------------

    @Test
    public void stubCoversFourOutcomeStatesAndThrowing() {
        OperationStub<String, String> accepting = OperationStub.accepting(x -> x + "!");
        assertOutcome(LocalFixture.compile(Flow.step(accepting)).run("in"), "in!");
        assertCallRecord(accepting, "in");

        Reason reason = Reason.of("NOPE", "nope");
        OperationStub<String, String> rejecting = OperationStub.rejecting(reason);
        FlowAssertions.assertRejected(
                LocalFixture.compile(Flow.step(rejecting)).run("in"), "NOPE");
        assertCallRecord(rejecting, "in");

        OperationStub<String, String> skipping = OperationStub.skipping(Reason.of("SKIP_1", "skipped"));
        FlowAssertions.assertSkipped(
                LocalFixture.compile(Flow.step(skipping)).run("in"), "SKIP_1");
        assertCallRecord(skipping, "in");

        Failure dead = Failure.of("DEAD", "dead");
        OperationStub<String, String> failing = OperationStub.failing(dead);
        FlowAssertions.assertFailed(LocalFixture.compile(Flow.step(failing)).run("in"), "DEAD");
        assertCallRecord(failing, "in");

        OperationStub<String, String> throwing =
                OperationStub.throwing(() -> new IllegalStateException("kaboom"));
        FlowAssertions.assertFailed(
                LocalFixture.compile(Flow.step(throwing)).run("in"), "OPERATION_EXCEPTION");
        assertCallRecord(throwing, "in");
    }

    @Test
    public void retryingStubKeepsStableInvocationIdAcrossAttempts() {
        OperationStub<String, String> failing =
                OperationStub.failing(Failure.of("UNSTABLE", "unstable"));
        Flow<String, String> flow = Flow.step(failing).persistentPolicy(PersistentPolicyStub.<String>counting(3, Duration.ZERO), s -> s);
        FlowResult<String> result = LocalFixture.compile(flow).run("in");

        FlowAssertions.assertFailed(result, "UNSTABLE");
        assertEquals(3, failing.callCount());
        String invocationId = failing.calls().get(0).invocationId();
        assertNotNull(invocationId);
        for (OperationStub.Call<String> call : failing.calls()) {
            assertEquals(invocationId, call.invocationId());
            assertEquals("in", call.input());
            // OperationContext 不暴露 attempt，记录为 0
            assertEquals(0, call.attempt());
        }
        assertEquals("in", failing.lastInput());
    }

    // ------------------------------------------------------------------
    // PolicyStub
    // ------------------------------------------------------------------

    @Test
    public void policyStubRecordsBeforeAfterAndGateDecisions() {
        PolicyStub<String> policy = PolicyStub.proceeding();
        OperationStub<String, String> body = OperationStub.accepting(x -> x + "!");
        Flow<String, String> flow = Flow.step(body).policy(policy, key -> "key:" + key);
        FlowAssertions.assertAccepted(LocalFixture.compile(flow).run("v"), "v!");

        assertEquals(1, policy.beforeCount());
        assertEquals(1, policy.afterCount());
        assertEquals("key:v", policy.beforeCalls().get(0).key());
        assertEquals(1, policy.beforeCalls().get(0).attempt());
        assertEquals(Outcome.Kind.ACCEPTED, policy.afterCalls().get(0).completion().kind());
        assertEquals(1, policy.afterCalls().get(0).attempt());

        PolicyStub<String> guard =
                PolicyStub.deciding(Gate.reject(Reason.of("DENIED", "denied")));
        Flow<String, String> guarded = Flow.step(body).policy(guard, key -> key);
        FlowAssertions.assertRejected(LocalFixture.compile(guarded).run("v"), "DENIED");
        // Gate.Reject 不进入 body，也不会触发 after
        assertEquals(1, guard.beforeCount());
        assertEquals(0, guard.afterCount());
    }

    // ------------------------------------------------------------------
    // TraceCollector
    // ------------------------------------------------------------------

    @Test
    public void traceCollectorCapturesTypesPathsAndClears() {
        OperationStub<Integer, Integer> plus = OperationStub.accepting(x -> x + 1);
        OperationStub<Integer, String> format = OperationStub.accepting(x -> "v" + x);
        TraceCollector trace = new TraceCollector();
        LocalFixture<Integer, String> fixture =
                LocalFixture.compile(Flow.step(plus).then(format), trace);

        FlowAssertions.assertAccepted(fixture.run(1), "v2");

        assertTrue(trace.eventCount() >= 6);
        assertEquals(FlowObserver.Type.FLOW_STARTED, trace.types().get(0));
        assertEquals(FlowObserver.Type.FLOW_COMPLETED,
                trace.types().get(trace.eventCount() - 1));
        assertTrue(trace.nodePaths(FlowObserver.Type.NODE_STARTED)
                .containsAll(Arrays.asList("$/0", "$/1")));
        assertEquals(1, trace.ofType(FlowObserver.Type.FLOW_STARTED).size());
        assertEquals("ACCEPTED", trace.ofType(FlowObserver.Type.FLOW_COMPLETED)
                .get(0).attributes().get("outcome"));

        trace.clear();
        assertTrue(trace.types().isEmpty());
        assertTrue(trace.events().isEmpty());
    }

    // ------------------------------------------------------------------
    // FlowAssertions（成功路径 + 失败消息可读性）
    // ------------------------------------------------------------------

    @Test
    public void assertionsValidateSuspensionAndCancellation() {
        ResumePoint<String> point = ResumePoint.named("smoke-point");
        Flow<String, String> flow = Flow.<String>identity().await(point)
                .then((context, resumed) ->
                        Outcome.accepted(resumed.state() + ":" + resumed.signal()));
        LocalFixture<String, String> fixture = LocalFixture.compile(flow);

        FlowResult<String> result = fixture.run("state");
        Suspension<String> suspension = FlowAssertions.assertSuspended(result, point);
        assertEquals("smoke-point", suspension.resumePoint());
        FlowAssertions.assertAccepted(fixture.resume(suspension, point, "sig"), "state:sig");
    }

    @Test
    public void assertionFailuresCarryExpectedAndActual() {
        Reason reason = Reason.of("R1", "rejected");
        FlowResult<String> rejected =
                LocalFixture.compile(Flow.<String, String>rejected(reason)).run("in");

        try {
            FlowAssertions.assertAccepted(rejected, "wanted");
            fail("expected AssertionError for Accepted mismatch");
        } catch (AssertionError error) {
            assertMessageContains(error, "expected Completed outcome to be Accepted", "R1");
        }

        try {
            FlowAssertions.assertRejected(rejected, "OTHER");
            fail("expected AssertionError for reason code mismatch");
        } catch (AssertionError error) {
            assertMessageContains(error, "expected:<[OTHER]>", "but was:<[R1]>");
        }

        try {
            FlowAssertions.assertFailed(rejected, "DEAD");
            fail("expected AssertionError for Failed mismatch");
        } catch (AssertionError error) {
            assertMessageContains(error, "expected Completed outcome to be Failed", "R1");
        }

        ResumePoint<String> point = ResumePoint.named("await-a");
        FlowResult<Resumed<String, String>> suspended = LocalFixture.compile(
                Flow.<String>identity().await(point)).run("in");
        try {
            FlowAssertions.assertCompleted(suspended);
            fail("expected AssertionError for Completed mismatch");
        } catch (AssertionError error) {
            assertMessageContains(error, "expected FlowResult to be Completed", "Suspended");
        }
        try {
            FlowAssertions.assertSuspended(suspended, ResumePoint.<String>named("await-b"));
            fail("expected AssertionError for resume point mismatch");
        } catch (AssertionError error) {
            assertMessageContains(error, "expected:<await-[b]>", "but was:<await-[a]>");
        }
    }

    // ------------------------------------------------------------------
    // LocalFixture
    // ------------------------------------------------------------------

    @Test
    public void localFixtureRunsThenChain() {
        OperationStub<Integer, Integer> plus = OperationStub.accepting(x -> x + 1);
        OperationStub<Integer, String> format = OperationStub.accepting(x -> "v" + x);
        TraceCollector trace = new TraceCollector();
        LocalFixture<Integer, String> fixture =
                LocalFixture.compile(Flow.step(plus).then(format), trace);

        assertEquals("v2", fixture.requireAccepted(1));
        assertEquals(1, plus.callCount());
        assertEquals(1, format.callCount());
        assertEquals(Integer.valueOf(1), plus.lastInput());
        assertEquals(Integer.valueOf(2), format.lastInput());
        assertFalse(trace.types().isEmpty());
    }

    @Test
    public void localFixtureReportsCancellation() throws Exception {
        final Cancellation cancellation = Cancellation.create();
        OperationStub<String, String> blocker = OperationStub.answering(
                new OperationStub.Answer<String, String>() {
                    @Override
                    public Outcome<String> answer(OperationContext context, String input) {
                        while (!context.cancellation().isCancelled()) {
                            try {
                                Thread.sleep(5);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                        return Outcome.accepted(input);
                    }
                });
        TraceCollector trace = new TraceCollector();
        LocalFixture<String, String> fixture =
                LocalFixture.compile(Flow.step(blocker), trace);

        CompletableFuture<FlowResult<String>> future = fixture.executable()
                .runAsync("payload", cancellation).toCompletableFuture();
        until(5000, new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return blocker.callCount() == 1;
            }
        });
        cancellation.cancel();

        String executionId = FlowAssertions.assertCancelled(future.get(5, TimeUnit.SECONDS));
        assertNotNull(executionId);
        assertEquals(1, trace.ofType(FlowObserver.Type.FLOW_CANCELLED).size());
    }

    // ------------------------------------------------------------------
    // DurableFixture
    // ------------------------------------------------------------------

    @Test
    public void durableFixtureDrivesStartSuspendResumeAndCancel() {
        ResumePoint<String> point = ResumePoint.named("smoke-durable");
        Flow<String, String> flow = Flow.<String>identity().await(point)
                .then((context, resumed) ->
                        Outcome.accepted(resumed.state() + ":" + resumed.signal()));
        DurableFixture<String, String> fixture = DurableFixture.compile(flow, "smoke-flow", 1);

        DurableResult<String> started = fixture.start("exec-1", "state");
        FlowAssertions.assertSuspended(started, "smoke-durable");
        assertEquals(DurableLifecycle.SUSPENDED,
                fixture.requireSnapshot("exec-1").lifecycle());

        DurableResult<String> resumed = fixture.resume("exec-1", point, "signal");
        FlowAssertions.assertAccepted(resumed, "state:signal");
        assertEquals(DurableLifecycle.COMPLETED,
                fixture.requireSnapshot("exec-1").lifecycle());
        assertFalse(fixture.snapshot("missing").isPresent());

        // 测试默认 flowId/flowVersion 的便捷重载
        DurableFixture<String, String> defaultFixture = DurableFixture.compile(flow);
        assertEquals("test", defaultFixture.executable().flowId());
        assertEquals(1, defaultFixture.executable().flowVersion());

        fixture.start("exec-2", "state");
        FlowAssertions.assertCancelled(fixture.cancel("exec-2"));
        assertEquals(DurableLifecycle.CANCELLED,
                fixture.requireSnapshot("exec-2").lifecycle());
    }

    @Test
    public void durableAssertionsValidateActiveWakeAt() {
        OperationStub<String, String> failing =
                OperationStub.failing(Failure.of("BOOM", "boom"));
        Flow<String, String> flow = Flow.step(failing)
                .persistentPolicy(PersistentPolicyStub.<String>counting(2, Duration.ofHours(1)), s -> s);
        DurableFixture<String, String> fixture = DurableFixture.compile(flow, "smoke-retry", 1);

        DurableResult.Active<String> active =
                FlowAssertions.assertActive(fixture.start("exec-r", "in"));
        assertTrue(active.wakeAt().isPresent());
        // 退避等待中的 ACTIVE 执行可 recover，仍返回 Active+wakeAt
        FlowAssertions.assertActive(fixture.recover("exec-r"));

        // 手工构造无 wakeAt 的 Active 结果：requireWakeAt=false 通过，默认重载失败
        DurableSnapshot snapshot = new DurableSnapshot("e", "f", 1,
                DurableSnapshot.CURRENT_FORMAT_ID, DurableSnapshot.CURRENT_FORMAT_VERSION,
                1, DurableLifecycle.ACTIVE, new byte[0],
                Collections.<String, StoredValue>emptyMap(),
                null, false);
        DurableResult.Active<String> noWake =
                new DurableResult.Active<String>(Optional.<Instant>empty(), snapshot);
        FlowAssertions.assertActive(noWake, false);
        try {
            FlowAssertions.assertActive(noWake);
            fail("expected AssertionError for missing wakeAt");
        } catch (AssertionError error) {
            assertMessageContains(error, "wakeAt");
        }
    }

    // ------------------------------------------------------------------
    // ParallelBarrier
    // ------------------------------------------------------------------

    @Test
    public void parallelBarrierProvesRealConcurrencyWithoutDeadlock() throws Exception {
        final ExecutorService callerExecutor = Executors.newFixedThreadPool(2);
        try {
            final ParallelBarrier barrier = new ParallelBarrier(2);
            final Branch<String, String> left = Branch.of("left",
                    (context, input) -> {
                        barrier.enter();
                        return Outcome.accepted(input + "L");
                    });
            final Branch<String, String> right = Branch.of("right",
                    (context, input) -> {
                        barrier.enter();
                        return Outcome.accepted(input + "R");
                    });
            Flow<String, String> parallel = Flow.parallel(left, right)
                    .join(results -> results.allAccepted()
                            .map(values -> values.get(left) + values.get(right)));
            TraceCollector trace = new TraceCollector();
            LocalFixture<String, String> fixture = LocalFixture.of(
                    Local.from(parallel).observer(trace).executor(callerExecutor).compile());

            // 异步驱动：若两分支未真正并发，awaitEntered 会超时返回 false（而非死锁挂死）
            CompletableFuture<FlowResult<String>> running = fixture.executable()
                    .runAsync("x").toCompletableFuture();
            assertTrue("parallel branches did not overlap within timeout",
                    barrier.awaitEntered(2000));
            barrier.release();

            FlowAssertions.assertAccepted(running.get(5, TimeUnit.SECONDS), "xLxR");
            assertEquals(1, trace.ofType(FlowObserver.Type.PARALLEL_STARTED).size());
            assertEquals(2, trace.ofType(FlowObserver.Type.PARALLEL_BRANCH_COMPLETED).size());
            assertEquals(1, trace.ofType(FlowObserver.Type.PARALLEL_JOINED).size());
        } finally {
            callerExecutor.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // PersistentPolicyStub
    // ------------------------------------------------------------------

    @Test
    public void persistentPolicyStubRetriesFailedUntilAttemptsExhausted() {
        OperationStub<String, String> failing =
                OperationStub.failing(Failure.of("STUB_FAIL", "stub failure"));
        Flow<String, String> flow = Flow.step(failing)
                .persistentPolicy(PersistentPolicyStub.<String>counting(3, Duration.ZERO), s -> s);
        FlowAssertions.assertFailed(LocalFixture.compile(flow).run("in"), "STUB_FAIL");
        assertEquals(3, failing.callCount());

        // 成功路径：首轮即通过，不重试
        OperationStub<String, String> ok = OperationStub.accepting(x -> x + "!");
        Flow<String, String> okFlow = Flow.step(ok)
                .persistentPolicy(PersistentPolicyStub.<String>counting(3, Duration.ofHours(1)), s -> s);
        FlowAssertions.assertAccepted(LocalFixture.compile(okFlow).run("in"), "in!");
        assertEquals(1, ok.callCount());

        try {
            PersistentPolicyStub.counting(0, Duration.ZERO);
            fail("maxAttempts=0 must be rejected");
        } catch (IllegalArgumentException expected) {
            assertMessageContains(new AssertionError(expected.getMessage()), "greater than 0");
        }
    }

    @Test
    public void assertAcceptedSupportsNullExpectation() {
        // 非 Accepted 结果 + null 期望：应产出可读的 AssertionError（而非 requireNonNull 抛出的 NPE）
        Reason reason = Reason.of("R1", "rejected");
        FlowResult<String> rejected =
                LocalFixture.compile(Flow.<String, String>rejected(reason)).run("in");
        try {
            FlowAssertions.assertAccepted(rejected, null);
            fail("expected AssertionError for non-Accepted outcome");
        } catch (AssertionError error) {
            assertMessageContains(error, "expected Completed outcome to be Accepted");
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static void assertOutcome(FlowResult<String> result, String expected) {
        FlowAssertions.assertAccepted(result, expected);
    }

    private static void assertCallRecord(OperationStub<String, String> stub, String input) {
        assertEquals(1, stub.callCount());
        OperationStub.Call<String> call = stub.calls().get(0);
        assertEquals(input, call.input());
        assertNotNull(call.invocationId());
        assertEquals(0, call.attempt());
    }

    private static void assertMessageContains(AssertionError error, String... fragments) {
        String message = error.getMessage();
        assertNotNull(message);
        for (String fragment : fragments) {
            assertTrue("message <" + message + "> should contain <" + fragment + ">",
                    message.contains(fragment));
        }
    }

    private static void until(int timeoutMillis, java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("condition not met within " + timeoutMillis + "ms");
            }
            Thread.sleep(5);
        }
    }

}
