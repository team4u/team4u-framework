package com.team4u.framework.flow;

import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import com.team4u.framework.flow.api.Branch;
import com.team4u.framework.flow.api.FlowObserver;
import com.team4u.framework.flow.api.Gate;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.PersistentPolicy;
import com.team4u.framework.flow.api.Policy;
import com.team4u.framework.flow.api.PolicyContext;
import com.team4u.framework.flow.api.ResumePoint;
import com.team4u.framework.flow.api.Retry;
import com.team4u.framework.flow.model.Cancellation;
import com.team4u.framework.flow.model.Completion;
import com.team4u.framework.flow.model.Failure;
import com.team4u.framework.flow.model.FlowResult;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Suspension;
import com.team4u.framework.flow.spi.OperationResolver;

/**
 * Control/Parallel/Suspend 交互综合测试：挂起的类型化与一次性、retry 的稳定 invocation id、
 * 嵌套 control 包装的包裹顺序、Policy 嵌套顺序与输出不可变性、异构 Parallel 的全等待与汇合、
 * 超时与取消对阻塞性 Policy/Join 回调/分支的边界约束，以及延迟事件抑制。
 */
public class ControlParallelSuspendTest {

    @Test
    public void localSuspensionIsTypedSingleUseAndKeepsExecution() {
        ResumePoint<String> point = ResumePoint.named("confirmation");
        Flow<Integer, String> flow = Flow.<Integer>identity()
                .await(point)
                .then((context, resumed) -> Outcome.accepted(
                        resumed.state() + ":" + resumed.signal()));
        LocalExecutable<Integer, String> local = Local.compile(flow);
        FlowResult<String> first = local.run(9);
        assertTrue(first instanceof FlowResult.Suspended<?>);
        FlowResult.Suspended<String> suspended = (FlowResult.Suspended<String>) first;
        assertTrue(suspended.awaiting(point));
        String executionId = suspended.suspension().executionId();
        assertEquals("9:yes", local.resume(suspended.suspension(), point, "yes")
                .requireAccepted());
        assertEquals(executionId, suspended.suspension().executionId());
        try {
            local.resume(suspended.suspension(), point, "again");
            fail("expected single-use failure");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("consumed"));
        }
    }

    @Test
    public void resumePointSnapshotsCreationPointAndSurvivesResume() {
        ResumePoint<String> point = ResumePoint.named("confirmation");
        Flow<Integer, String> flow = Flow.<Integer>identity()
                .await(point)
                .then((context, resumed) -> Outcome.accepted(resumed.signal()));
        LocalExecutable<Integer, String> local = Local.compile(flow);
        FlowResult.Suspended<String> suspended = (FlowResult.Suspended<String>) local.run(7);
        Suspension<String> suspension = suspended.suspension();
        assertEquals("confirmation", suspension.resumePoint());
        assertEquals("yes", local.resume(suspension, point, "yes").requireAccepted());
        assertEquals("confirmation", suspension.resumePoint());
    }

    @Test
    public void retryUsesEntryAndStableInvocationId() {
        final AtomicInteger calls = new AtomicInteger();
        final List<String> ids = new ArrayList<String>();
        Flow<String, String> flow = Flow.step(
                (Operation<String, String>) (context, input) -> {
            ids.add(context.invocationId());
            return calls.incrementAndGet() < 3
                    ? Outcome.failed(Failure.of("RETRY", "retry"))
                    : Outcome.accepted(input + "-ok");
        }).retry(Retry.maxAttempts(3));
        assertEquals("value-ok", Local.compile(flow).run("value").requireAccepted());
        assertEquals(3, calls.get());
        int distinct = 0;
        List<String> seen = new ArrayList<String>();
        for (String id : ids) {
            if (!seen.contains(id)) {
                seen.add(id);
                distinct++;
            }
        }
        assertEquals(1, distinct);
    }

    @Test
    public void lastControlWrapperIsOutermost() {
        final AtomicInteger retryOutsideCalls = new AtomicInteger();
        Operation<String, String> firstSlow = (context, input) -> {
            if (retryOutsideCalls.incrementAndGet() == 1)
                ignoreInterrupts(Duration.ofMillis(80));
            return Outcome.accepted(input);
        };
        Flow<String, String> retryOutside = Flow.step(firstSlow)
                .timeout(Duration.ofMillis(15))
                .retry(Retry.maxAttempts(2));
        assertEquals("value", Local.compile(retryOutside)
                .run("value").requireAccepted());
        assertEquals(2, retryOutsideCalls.get());

        final AtomicInteger timeoutOutsideCalls = new AtomicInteger();
        Operation<String, String> alwaysSlow = (context, input) -> {
            timeoutOutsideCalls.incrementAndGet();
            ignoreInterrupts(Duration.ofMillis(80));
            return Outcome.accepted(input);
        };
        FlowResult.Completed<String> timed = (FlowResult.Completed<String>) Local.compile(
                Flow.step(alwaysSlow).retry(Retry.maxAttempts(2))
                        .timeout(Duration.ofMillis(15))).run("value");
        assertEquals("TIMEOUT",
                ((Outcome.Failed<String>) timed.outcome()).failure().code());
        assertEquals(1, timeoutOutsideCalls.get());
    }

    @Test
    public void ordinaryPoliciesNestLastCallOutsideAndCannotRewriteOutput() {
        final List<String> order = new ArrayList<String>();
        Policy<String> inner = policy("inner", order);
        Policy<String> outer = policy("outer", order);
        Flow<String, String> flow = Flow.step(
                        (Operation<String, String>) (context, input) -> {
                            order.add("body");
                            return Outcome.accepted(input);
                        })
                .policy(inner, value -> value)
                .policy(outer, value -> value);
        assertEquals("x", Local.compile(flow).run("x").requireAccepted());
        assertEquals(Arrays.asList("outer-before", "inner-before", "body",
                "inner-after-ACCEPTED", "outer-after-ACCEPTED"), order);
    }

    @Test
    public void heterogeneousParallelWaitsForAllAndUsesTokens() throws Exception {
        final CountDownLatch entered = new CountDownLatch(2);
        final CountDownLatch release = new CountDownLatch(1);
        final Branch<String, Integer> length = Branch.of("length", (context, input) -> {
            entered.countDown();
            release.await();
            return Outcome.accepted(input.length());
        });
        final Branch<String, String> upper = Branch.of("upper", (context, input) -> {
            entered.countDown();
            release.await();
            return Outcome.accepted(input.toUpperCase());
        });
        Flow<String, String> flow = Flow.parallel(length, upper).join(results ->
                results.allAccepted().map(values ->
                        values.get(upper) + ":" + values.get(length)));
        final AtomicReference<FlowResult<String>> result = new AtomicReference<FlowResult<String>>();
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                result.set(Local.compile(flow).run("flow"));
            }
        });
        thread.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        release.countDown();
        thread.join();
        assertEquals("FLOW:4", result.get().requireAccepted());
    }

    @Test
    public void timeoutDiscardsLateResult() {
        Flow<String, String> flow = Flow.step(
                (Operation<String, String>) (context, input) -> {
            long until = System.nanoTime() + Duration.ofMillis(150).toNanos();
            while (System.nanoTime() < until) Thread.interrupted();
            return Outcome.accepted("late");
        }).timeout(Duration.ofMillis(20));
        FlowResult.Completed<String> result = (FlowResult.Completed<String>)
                Local.compile(flow).run("input");
        assertTrue(result.outcome() instanceof Outcome.Failed<?>);
        assertEquals("TIMEOUT", ((Outcome.Failed<String>) result.outcome()).failure().code());
    }

    @Test
    public void parallelTimeoutSuppressesLateAcceptedEventsAndReportsEveryBranch()
            throws Exception {
        final CountDownLatch late = new CountDownLatch(2);
        Operation<String, String> ignored = (context, input) -> {
            ignoreInterrupts(Duration.ofMillis(120));
            late.countDown();
            return Outcome.accepted(input);
        };
        Branch<String, String> left = Branch.of("late-left", ignored);
        Branch<String, String> right = Branch.of("late-right", ignored);
        Flow<String, String> flow = Flow.parallel(left, right)
                .join(results -> results.outcome(left))
                .timeout(Duration.ofMillis(20));
        final List<FlowObserver.Event> events = Collections.synchronizedList(new ArrayList<FlowObserver.Event>());
        FlowResult.Completed<String> result = (FlowResult.Completed<String>) Local.compile(
                flow, OperationResolver.rejecting(), events::add).run("input");
        assertEquals("TIMEOUT",
                ((Outcome.Failed<String>) result.outcome()).failure().code());
        assertTrue(late.await(2, TimeUnit.SECONDS));
        Thread.sleep(30);

        boolean completedSeen = false;
        long acceptedAfterTerminal = 0;
        List<FlowObserver.Event> branches = new ArrayList<FlowObserver.Event>();
        synchronized (events) {
            for (FlowObserver.Event event : events) {
                if (event.type() == FlowObserver.Type.FLOW_COMPLETED) {
                    completedSeen = true;
                }
                if (completedSeen && event.type() == FlowObserver.Type.NODE_COMPLETED
                        && "ACCEPTED".equals(event.attributes().get("outcome"))) {
                    acceptedAfterTerminal++;
                }
                if (event.type() == FlowObserver.Type.PARALLEL_BRANCH_COMPLETED) {
                    branches.add(event);
                }
            }
        }
        assertEquals(0, acceptedAfterTerminal);
        assertEquals(2, branches.size());
        for (FlowObserver.Event event : branches) {
            assertEquals("FAILED", event.attributes().get("outcome"));
            assertEquals("TIMEOUT", event.attributes().get("code"));
        }
    }

    @Test
    public void parallelCancellationReportsEveryBranchAndSuppressesLateAcceptedEvents()
            throws Exception {
        final CountDownLatch entered = new CountDownLatch(2);
        final CountDownLatch late = new CountDownLatch(2);
        Operation<String, String> ignored = (context, input) -> {
            entered.countDown();
            ignoreInterrupts(Duration.ofMillis(120));
            late.countDown();
            return Outcome.accepted(input);
        };
        Branch<String, String> left = Branch.of("cancel-left", ignored);
        Branch<String, String> right = Branch.of("cancel-right", ignored);
        Flow<String, String> flow = Flow.parallel(left, right)
                .join(results -> results.outcome(left));
        final List<FlowObserver.Event> events = Collections.synchronizedList(new ArrayList<FlowObserver.Event>());
        Cancellation cancellation = Cancellation.create();
        CompletableFuture<FlowResult<String>> future = Local.compile(flow, OperationResolver.rejecting(), events::add)
                .runAsync("input", cancellation).toCompletableFuture();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        cancellation.cancel();
        assertTrue(future.get(2, TimeUnit.SECONDS) instanceof FlowResult.Cancelled<?>);
        assertTrue(late.await(2, TimeUnit.SECONDS));
        Thread.sleep(30);

        boolean cancelledSeen = false;
        long lateAccepted = 0;
        List<FlowObserver.Event> branches = new ArrayList<FlowObserver.Event>();
        synchronized (events) {
            for (FlowObserver.Event event : events) {
                if (event.type() == FlowObserver.Type.FLOW_CANCELLED) {
                    cancelledSeen = true;
                }
                if (cancelledSeen && event.type() == FlowObserver.Type.NODE_COMPLETED
                        && "ACCEPTED".equals(event.attributes().get("outcome"))) {
                    lateAccepted++;
                }
                if (event.type() == FlowObserver.Type.PARALLEL_BRANCH_COMPLETED) {
                    branches.add(event);
                }
            }
        }
        assertEquals(2, branches.size());
        for (FlowObserver.Event event : branches) {
            assertEquals("FAILED", event.attributes().get("outcome"));
            assertEquals("CANCELLED", event.attributes().get("code"));
        }
        assertEquals(0, lateAccepted);
    }

    @Test
    public void timeoutBoundsBlockingPolicyAndJoinCallbacks() {
        Policy<String> blocking = new Policy<String>() {
            @Override public Gate before(PolicyContext context, String key) {
                ignoreInterrupts(Duration.ofMillis(300));
                return Gate.proceed();
            }
        };
        Flow<String, String> policyFlow = Flow.<String>identity()
                .policy(blocking, value -> value)
                .timeout(Duration.ofMillis(20));
        assertTimesOutQuickly(policyFlow);

        Policy<String> blockingAfter = new Policy<String>() {
            @Override public Gate before(PolicyContext context, String key) {
                return Gate.proceed();
            }
            @Override public void after(PolicyContext context, String key,
                                        Completion completion) {
                ignoreInterrupts(Duration.ofMillis(300));
            }
        };
        assertTimesOutQuickly(Flow.<String>identity()
                .policy(blockingAfter, value -> value)
                .timeout(Duration.ofMillis(20)));

        PersistentPolicy<String, Integer> persistentBefore = persistentBlocking(true);
        assertTimesOutQuickly(Flow.<String>identity()
                .persistentPolicy(persistentBefore, value -> value)
                .timeout(Duration.ofMillis(20)));
        PersistentPolicy<String, Integer> persistentAfter = persistentBlocking(false);
        assertTimesOutQuickly(Flow.<String>identity()
                .persistentPolicy(persistentAfter, value -> value)
                .timeout(Duration.ofMillis(20)));

        Branch<String, String> branch = Branch.of("branch", Flow.identity());
        Flow<String, String> joinFlow = Flow.parallel(branch)
                .<String>join(results -> {
                    ignoreInterrupts(Duration.ofMillis(300));
                    return results.outcome(branch);
                })
                .timeout(Duration.ofMillis(20));
        assertTimesOutQuickly(joinFlow);

        Branch<String, String> slowFirst = Branch.of("slow-first", (context, input) -> {
            ignoreInterrupts(Duration.ofMillis(300));
            return Outcome.accepted(input);
        });
        Branch<String, String> slowSecond = Branch.of("slow-second", (context, input) -> {
            ignoreInterrupts(Duration.ofMillis(300));
            return Outcome.accepted(input);
        });

        // 验证 slow parallel 的 wait-all 与 timeout 协同合同：
        // 1. 逻辑结果首先落定为 TIMEOUT 失败，晚到结果被严格抑制；
        // 2. 物理返回受 True Wait-All 退出保证约束：必须等待所有已启动分支的工作线程退出。
        //    对于忽略中断的非协作代码，主线程会等待其物理结束（耗时 >= 250ms），确保调用返回后无并发线程泄漏。
        long startNanos = System.nanoTime();
        FlowResult.Completed<String> slowParallelResult = (FlowResult.Completed<String>)
                Local.compile(Flow.parallel(slowFirst, slowSecond)
                        .join(results -> results.outcome(slowFirst))
                        .timeout(Duration.ofMillis(20))).run("input");
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

        assertTrue(slowParallelResult.outcome() instanceof Outcome.Failed<?>);
        assertEquals("TIMEOUT", ((Outcome.Failed<String>) slowParallelResult.outcome()).failure().code());
        assertTrue("True wait-all contract must wait for non-cooperative branches to exit physically: " + elapsedMillis + "ms",
                elapsedMillis >= 250 && elapsedMillis < 3000);
    }


    private static PersistentPolicy<String, Integer> persistentBlocking(final boolean before) {
        return new PersistentPolicy<String, Integer>() {
            @Override public Integer initialState(String key) { return 0; }
            @Override public Before<Integer> before(PolicyContext context, String key, Integer state) {
                if (before) ignoreInterrupts(Duration.ofMillis(300));
                return PersistentPolicy.proceed(state);
            }
            @Override public After<Integer> after(PolicyContext context, String key, Integer state,
                                         Completion completion) {
                if (!before) ignoreInterrupts(Duration.ofMillis(300));
                return PersistentPolicy.returning(state);
            }
        };
    }

    private static void assertTimesOutQuickly(Flow<String, String> flow) {
        long started = System.nanoTime();
        FlowResult.Completed<String> result = (FlowResult.Completed<String>)
                Local.compile(flow).run("input");
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertTrue(result.outcome() instanceof Outcome.Failed<?>);
        assertEquals("TIMEOUT", ((Outcome.Failed<String>) result.outcome()).failure().code());
        assertTrue("callback exceeded timeout: " + elapsedMillis + "ms", elapsedMillis < 200);
    }

    private static void ignoreInterrupts(Duration duration) {
        long until = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < until) Thread.interrupted();
    }

    private static Policy<String> policy(final String name, final List<String> order) {
        return new Policy<String>() {
            @Override public Gate before(PolicyContext context, String key) {
                order.add(name + "-before");
                return Gate.proceed();
            }

            @Override public void after(PolicyContext context, String key,
                                        Completion completion) {
                order.add(name + "-after-" + completion.kind());
            }
        };
    }
}
