package com.team4u.framework.flow;

import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import com.team4u.framework.flow.api.Branch;
import com.team4u.framework.flow.api.Gate;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.api.PersistentPolicy;
import com.team4u.framework.flow.api.Policy;
import com.team4u.framework.flow.api.PolicyContext;
import com.team4u.framework.flow.api.ResumePoint;
import com.team4u.framework.flow.compiler.Compiler;
import com.team4u.framework.flow.model.Cancellation;
import com.team4u.framework.flow.model.Completion;
import com.team4u.framework.flow.model.Failure;
import com.team4u.framework.flow.model.FlowBuildException;
import com.team4u.framework.flow.model.FlowResult;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Reason;
import com.team4u.framework.flow.model.Resumed;

/**
 * Policy 与能力边界的契约验证：PersistentPolicy 的 waitUntil/retryAt 状态机、
 * Compiler 对 Parallel 内 await/PersistentPolicy 及重名节点的拒绝、OperationContext.await 行为、
 * 异步线程池执行，以及取消优先于中断与回调语义。
 */
public class PolicyAndCapabilityTest {

    @Test
    public void persistentPolicyWaitsAndRetriesFromEntry() {
        final AtomicInteger operations = new AtomicInteger();
        final List<Integer> attempts = new ArrayList<Integer>();
        PersistentPolicy<String, Integer> policy = new PersistentPolicy<String, Integer>() {
            @Override public Integer initialState(String key) { return 0; }

            @Override public Before<Integer> before(
                    PolicyContext context, String key, Integer state) {
                attempts.add(context.attempt());
                if (state == 0) return PersistentPolicy.waitUntil(
                        Instant.now().plusMillis(5), 1);
                return PersistentPolicy.proceed(state);
            }

            @Override public After<Integer> after(PolicyContext context, String key,
                                                  Integer state, Completion completion) {
                return context.attempt() == 1
                        ? PersistentPolicy.retryAt(Instant.now(), state + 1)
                        : PersistentPolicy.returning(state);
            }
        };
        Flow<String, String> flow = Flow.step(
                        (Operation<String, String>) (context, input) ->
                                operations.incrementAndGet() == 1
                                        ? Outcome.failed(Failure.of("RETRY", "retry"))
                                        : Outcome.accepted(input))
                .persistentPolicy(policy, value -> value);
        assertEquals("entry", Local.compile(flow).run("entry").requireAccepted());
        assertEquals(2, operations.get());
        assertEquals(Arrays.asList(1, 1, 2), attempts);
    }

    @Test
    public void compilerRejectsParallelContinuationsAndDuplicateNames() {
        Branch<String, Resumed<String, String>> awaiting = Branch.of("awaiting",
                Flow.<String>identity().await(ResumePoint.named("parallel-point")));
        assertProblem("PARALLEL_AWAIT", () -> Local.compile(
                Flow.parallel(awaiting).join(results -> results.outcome(awaiting))));

        PersistentPolicy<String, Integer> policy = new PersistentPolicy<String, Integer>() {
            @Override public Integer initialState(String key) { return 0; }
            @Override public Before<Integer> before(PolicyContext context, String key, Integer state) {
                return PersistentPolicy.proceed(state);
            }
            @Override public After<Integer> after(PolicyContext context, String key, Integer state,
                                         Completion completion) {
                return PersistentPolicy.returning(state);
            }
        };
        Branch<String, String> persistent = Branch.of("persistent",
                Flow.<String>identity().persistentPolicy(policy, value -> value));
        assertProblem("PARALLEL_PERSISTENT_POLICY", () -> Local.compile(
                Flow.parallel(persistent).join(results -> results.outcome(persistent))));

        ResumePoint<String> first = ResumePoint.named("duplicate");
        ResumePoint<Integer> second = ResumePoint.named("duplicate");
        Flow<String, Resumed<Resumed<String, String>, Integer>> duplicatePoints =
                Flow.<String>identity().await(first).await(second);
        assertProblem("DUPLICATE_RESUME_POINT", () -> Local.compile(duplicatePoints));

        Flow<String, String> duplicateScopes = Flow.scope(
                "same", Flow.scope("same", Flow.<String>identity()));
        assertProblem("DUPLICATE_SCOPE", () -> Local.compile(duplicateScopes));

        Branch<String, String> firstBranch = Branch.of("same-name", Flow.identity());
        Branch<String, String> secondBranch = Branch.of("same-name", Flow.identity());
        assertProblem("DUPLICATE_BRANCH", () -> Local.compile(
                Flow.parallel(firstBranch, secondBranch)
                        .join(results -> results.outcome(firstBranch))));

        // 两个互不相干的并行块复用相同分支名（如 left/right）是合法的：
        // 分支名仅要求同一并行块内唯一，不要求全局唯一。
        Branch<String, String> left = Branch.of("left", Flow.<String>identity());
        Branch<String, String> right = Branch.of("right", Flow.<String>identity());
        Flow<String, String> firstBlock = Flow.parallel(left, right)
                .join(results -> results.outcome(left));
        Branch<String, String> reusedLeft = Branch.of("left", Flow.<String>identity());
        Branch<String, String> reusedRight = Branch.of("right", Flow.<String>identity());
        Flow<String, String> secondBlock = Flow.parallel(reusedLeft, reusedRight)
                .join(results -> results.outcome(reusedRight));
        Flow<String, String> reusedNames = firstBlock.then(secondBlock);
        assertEquals("value", Local.compile(reusedNames).run("value").requireAccepted());

        Flow<String, Resumed<Resumed<String, String>, String>> reusedPoint =
                Flow.<String>identity().await(first).await(first);
        assertProblem("DUPLICATE_RESUME_POINT", () -> Local.compile(reusedPoint));
    }

    @Test
    public void contextAwaitUnwrapsFailureAndAsyncUsesExecutor() {
        final Thread mainThread = Thread.currentThread();
        Operation<String, Boolean> asyncOp = (context, input) ->
                Outcome.accepted(Thread.currentThread() != mainThread);
        assertTrue(Local.compile(Flow.step(asyncOp)).runAsync("input")
                .toCompletableFuture().join().requireAccepted());

        Operation<String, String> await = (context, input) -> Outcome.accepted(
                context.await(CompletableFuture.completedFuture(input + "-done")));
        assertEquals("x-done", Local.compile(Flow.step(await)).run("x").requireAccepted());

        Operation<String, String> failedAwait = (context, input) -> {
            CompletableFuture<String> future = new CompletableFuture<String>();
            future.completeExceptionally(new IllegalArgumentException("bad-stage"));
            return Outcome.accepted(context.await(future));
        };
        FlowResult.Completed<String> result = (FlowResult.Completed<String>)
                Local.compile(Flow.step(failedAwait)).run("x");
        assertEquals("OPERATION_EXCEPTION",
                ((Outcome.Failed<String>) result.outcome()).failure().code());
    }

    @Test
    public void asyncCancellationProducesCancelledLifecycle() throws Exception {
        Cancellation cancellation = Cancellation.create();
        Operation<String, String> blocking = (context, input) -> {
            while (!context.cancellation().isCancelled()) Thread.sleep(10);
            context.cancellation().throwIfCancelled();
            return Outcome.accepted(input);
        };
        CompletableFuture<FlowResult<String>> future = Local.compile(Flow.step(blocking))
                .runAsync("x", cancellation).toCompletableFuture();
        Thread.sleep(20);
        cancellation.cancel();
        assertTrue(future.get(2, TimeUnit.SECONDS) instanceof FlowResult.Cancelled<?>);
    }

    @Test
    public void cancellationWinsOverInterruptIgnoringCallbacks() throws Exception {
        Operation<String, String> lateOperation = (context, input) -> {
            ignoreInterrupts(Duration.ofMillis(80));
            return Outcome.accepted("late");
        };
        assertCancelled(Flow.step(lateOperation));

        Policy<String> lateBefore = new Policy<String>() {
            @Override public Gate before(PolicyContext context, String key) {
                ignoreInterrupts(Duration.ofMillis(80));
                return Gate.reject(Reason.of("LATE", "late"));
            }
        };
        assertCancelled(Flow.<String>identity().policy(lateBefore, value -> value));

        Policy<String> lateAfter = new Policy<String>() {
            @Override public Gate before(PolicyContext context, String key) {
                return Gate.proceed();
            }
            @Override public void after(PolicyContext context, String key,
                                        Completion completion) {
                ignoreInterrupts(Duration.ofMillis(80));
            }
        };
        assertCancelled(Flow.<String>identity().policy(lateAfter, value -> value));

        PersistentPolicy<String, Integer> latePersistentBefore = new PersistentPolicy<String, Integer>() {
            @Override public Integer initialState(String key) { return 0; }
            @Override public Before<Integer> before(PolicyContext context, String key, Integer state) {
                ignoreInterrupts(Duration.ofMillis(80));
                return PersistentPolicy.reject(Reason.of("LATE", "late"), state);
            }
            @Override public After<Integer> after(PolicyContext context, String key, Integer state,
                                         Completion completion) {
                return PersistentPolicy.returning(state);
            }
        };
        assertCancelled(Flow.<String>identity()
                .persistentPolicy(latePersistentBefore, value -> value));

        PersistentPolicy<String, Integer> latePersistentAfter = new PersistentPolicy<String, Integer>() {
            @Override public Integer initialState(String key) { return 0; }
            @Override public Before<Integer> before(PolicyContext context, String key, Integer state) {
                return PersistentPolicy.proceed(state);
            }
            @Override public After<Integer> after(PolicyContext context, String key, Integer state,
                                         Completion completion) {
                ignoreInterrupts(Duration.ofMillis(80));
                return PersistentPolicy.returning(state);
            }
        };
        assertCancelled(Flow.<String>identity()
                .persistentPolicy(latePersistentAfter, value -> value));

        Branch<String, String> branch = Branch.of("late-join", Flow.identity());
        assertCancelled(Flow.parallel(branch).join(results -> {
            ignoreInterrupts(Duration.ofMillis(80));
            return results.outcome(branch);
        }));
    }

    private static void assertCancelled(Flow<String, String> flow) throws Exception {
        Cancellation cancellation = Cancellation.create();
        CompletableFuture<FlowResult<String>> future = Local.compile(flow)
                .runAsync("value", cancellation).toCompletableFuture();
        Thread.sleep(20);
        cancellation.cancel();
        assertTrue(future.get(2, TimeUnit.SECONDS) instanceof FlowResult.Cancelled<?>);
    }

    private static void ignoreInterrupts(Duration duration) {
        long until = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < until) Thread.interrupted();
    }

    private static void assertProblem(String code, Runnable compilation) {
        try {
            compilation.run();
            fail("expected " + code);
        } catch (FlowBuildException expected) {
            assertTrue(expected.problems().stream().anyMatch(problem -> problem.code().equals(code)));
        }
    }

    @Test
    public void policyThrowingFlowExecutionExceptionPreservesFailureCode() {
        Policy<String> customExceptionPolicy = new Policy<String>() {
            @Override
            public Gate before(PolicyContext context, String key) {
                throw new com.team4u.framework.flow.model.FlowExecutionException(
                        "CUSTOM_RATE_LIMIT", "Custom rate limit exceeded");
            }
        };

        Flow<String, String> flow = Flow.<String>identity()
                .policy(customExceptionPolicy, value -> value);

        FlowResult<String> result = Local.compile(flow).run("test");
        assertTrue(result instanceof FlowResult.Completed<?>);
        Outcome<String> outcome = ((FlowResult.Completed<String>) result).outcome();
        assertTrue(outcome instanceof Outcome.Failed<?>);
        Failure failure = ((Outcome.Failed<?>) outcome).failure();
        assertEquals("CUSTOM_RATE_LIMIT", failure.code());
        assertEquals("Custom rate limit exceeded", failure.message());
    }
}
