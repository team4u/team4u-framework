package com.team4u.framework.flow;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 行为矩阵综合测试：覆盖 use 合并/降级/恢复语义、scope/firstApplicable 作用域入口保留、
 * 普通 Policy 与 PersistentPolicy 决策、context.await 取消传播、Parallel 声明顺序与共享可变输入、
 * Local 挂起恢复的所有权与并发复用、编译期重复 branch/解析失败诊断，以及公共契约的 null/blank 拒绝。
 */
public class BehaviorMatrixTest {

    static final class State {
        private final String value;

        public State(String value) {
            this.value = value;
        }

        public String value() { return value; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            State state = (State) o;
            return Objects.equals(value, state.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(value);
        }
    }

    static final class Request {
        private final String tenant;

        public Request(String tenant) {
            this.tenant = tenant;
        }

        public String tenant() { return tenant; }
    }

    @Test
    public void useMergesOnlyAcceptedAndControlTriggersAreExclusive() {
        Reason no = Reason.of("NO", "no");
        Failure failure = Failure.of("FAILED", "failed");
        List<Outcome<String>> terminal = Arrays.asList(
                Outcome.rejected(no), Outcome.skipped(no), Outcome.failed(failure));
        for (Outcome<String> operationOutcome : terminal) {
            final AtomicInteger merges = new AtomicInteger();
            Operation<String, String> operation = (context, input) -> operationOutcome;
            FlowResult.Completed<State> result = (FlowResult.Completed<State>) Local.compile(
                    Flow.<State>identity().use(operation, State::value, (state, output) -> {
                        merges.incrementAndGet();
                        return new State(output);
                    })).run(new State("entry"));
            assertEquals(operationOutcome.kind(), result.outcome().kind());
            assertEquals(0, merges.get());
        }

        final AtomicInteger recovery = new AtomicInteger();
        Flow<String, String> rejected = Flow.<String, String>rejected(no)
                .recoverWith(Flow.step((context, value) -> {
                    recovery.incrementAndGet();
                    return Outcome.accepted("wrong");
                }));
        assertTrue(((FlowResult.Completed<String>) Local.compile(rejected).run("entry"))
                .outcome() instanceof Outcome.Rejected<?>);
        assertEquals(0, recovery.get());

        final AtomicInteger candidates = new AtomicInteger();
        Flow<String, String> failedCandidate = Flow.firstApplicable(
                Flow.failed(failure), Flow.step((context, input) -> {
                    candidates.incrementAndGet();
                    return Outcome.accepted("wrong");
                }));
        assertTrue(((FlowResult.Completed<String>) Local.compile(failedCandidate).run("entry"))
                .outcome() instanceof Outcome.Failed<?>);
        assertEquals(0, candidates.get());
    }

    @Test
    public void scopeRecoveryAndFirstApplicableUseOriginalEntry() {
        Flow<String, Integer> primary = Flow.step(
                        (Operation<String, Integer>) (context, input) ->
                                Outcome.accepted(input.length()))
                .then(Flow.failed(Failure.of("BROKEN", "broken")));
        Flow<String, Integer> recovered = Flow.scope("transaction", primary)
                .recoverWith(Flow.step((context, recovery) -> {
                    assertEquals("original", recovery.input());
                    assertEquals("BROKEN", recovery.failure().code());
                    return Outcome.accepted(42);
                }));
        assertEquals(Integer.valueOf(42),
                Local.compile(recovered).run("original").requireAccepted());

        Flow<String, String> first = Flow.step(
                        (Operation<String, Integer>) (context, input) ->
                                Outcome.accepted(input.length()))
                .then(Flow.skipped(Reason.of("NEXT", "next")));
        Flow<String, String> applicable = Flow.firstApplicable(first,
                Flow.step((context, input) -> Outcome.accepted(input + "-second")));
        assertEquals("original-second",
                Local.compile(applicable).run("original").requireAccepted());
    }

    @Test
    public void ordinaryAndPersistentPoliciesCoverTypedKeysAndTerminalDecisions() {
        final List<String> keys = new ArrayList<String>();
        Policy<String> rejecting = new Policy<String>() {
            @Override
            public Gate before(PolicyContext context, String key) {
                keys.add(key);
                return Gate.reject(Reason.of("LIMIT", "limited"));
            }
        };
        FlowResult.Completed<Request> rejected = (FlowResult.Completed<Request>) Local.compile(
                Flow.<Request>identity().policy(rejecting, Request::tenant))
                .run(new Request("tenant-1"));
        assertTrue(rejected.outcome() instanceof Outcome.Rejected<?>);
        assertEquals(Collections.singletonList("tenant-1"), keys);

        Policy<String> failing = new Policy<String>() {
            @Override
            public Gate before(PolicyContext context, String key) {
                return Gate.fail(Failure.of("GATE", "failed"));
            }
        };
        assertTrue(((FlowResult.Completed<Request>) Local.compile(
                Flow.<Request>identity().policy(failing, Request::tenant))
                .run(new Request("tenant-2"))).outcome() instanceof Outcome.Failed<?>);

        assertPersistentTerminal(PersistentPolicy.reject(
                Reason.of("PERSISTENT_NO", "no"), 1), Outcome.Kind.REJECTED);
        assertPersistentTerminal(PersistentPolicy.fail(
                Failure.of("PERSISTENT_FAIL", "failed"), 1), Outcome.Kind.FAILED);
    }

    @Test
    public void contextAwaitCancellationCancelsStageAndLifecycle() throws Exception {
        final CompletableFuture<String> stage = new CompletableFuture<String>();
        final CountDownLatch entered = new CountDownLatch(1);
        Operation<String, String> operation = (context, input) -> {
            entered.countDown();
            return Outcome.accepted(context.await(stage));
        };
        Cancellation cancellation = Cancellation.create();
        CompletableFuture<FlowResult<String>> result = Local.compile(Flow.step(operation))
                .runAsync("input", cancellation).toCompletableFuture();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        cancellation.cancel();
        assertTrue(result.get(2, TimeUnit.SECONDS) instanceof FlowResult.Cancelled<?>);
        assertTrue(stage.isCancelled());
    }

    @Test
    public void parallelHelpersUseDeclarationOrderAndShareInputReference() {
        final Object shared = new Object();
        final AtomicBoolean sameReference = new AtomicBoolean(true);
        final Branch<Object, String> first = Branch.of("first", (context, input) -> {
            sameReference.compareAndSet(true, input == shared);
            Thread.sleep(30);
            return Outcome.accepted("first");
        });
        final Branch<Object, String> second = Branch.of("second", (context, input) -> {
            sameReference.compareAndSet(true, input == shared);
            return Outcome.accepted("second");
        });
        Flow<Object, String> firstAccepted = Flow.parallel(first, second).join(results -> {
            assertEquals(Arrays.asList(first, second), results.branches());
            return results.firstAccepted().map(value -> (String) value);
        });
        assertEquals("first", Local.compile(firstAccepted).run(shared).requireAccepted());
        assertTrue(sameReference.get());

        Flow<Object, String> quorum = Flow.parallel(first, second).join(results ->
                results.quorum(2).map(values -> values.get(first) + ":" + values.get(second)));
        assertEquals("first:second", Local.compile(quorum).run(shared).requireAccepted());
        Flow<Object, List<String>> collected = Flow.parallel(first, second)
                .join(results -> results.homogeneousCollect()
                        .map(list -> {
                            List<String> casted = new ArrayList<String>();
                            for (Object item : list) {
                                casted.add((String) item);
                            }
                            return casted;
                        }));
        assertEquals(Arrays.asList("first", "second"),
                Local.compile(collected).run(shared).requireAccepted());

        final AtomicInteger mutable = new AtomicInteger();
        Branch<AtomicInteger, Integer> incrementLeft = Branch.of(
                "mutable-left", (context, input) -> Outcome.accepted(
                        input.incrementAndGet()));
        Branch<AtomicInteger, Integer> incrementRight = Branch.of(
                "mutable-right", (context, input) -> Outcome.accepted(
                        input.incrementAndGet()));
        Flow<AtomicInteger, Integer> mutation = Flow.parallel(
                        incrementLeft, incrementRight)
                .join(results -> results.allAccepted().map(ignored -> mutable.get()));
        assertEquals(Integer.valueOf(2),
                Local.compile(mutation).run(mutable).requireAccepted());
        assertEquals("the framework deliberately does not copy mutable branch input",
                2, mutable.get());
    }

    @Test
    public void localSuspensionRejectsWrongOwnerPointAndConcurrentReuse() throws Exception {
        final ResumePoint<String> point = ResumePoint.named("point");
        final LocalExecutable<String, Resumed<String, String>> first =
                Local.compile(Flow.<String>identity().await(point));
        final LocalExecutable<String, Resumed<String, String>> other =
                Local.compile(Flow.<String>identity().await(point));
        final Suspension<Resumed<String, String>> token =
                ((FlowResult.Suspended<Resumed<String, String>>) first.run("state"))
                        .suspension();
        assertThrowsException(IllegalArgumentException.class,
                () -> first.resume(token, ResumePoint.named("wrong"), "signal"));
        assertThrowsException(IllegalArgumentException.class,
                () -> other.resume(token, point, "signal"));
        assertEquals("signal", first.resume(token, point, "signal")
                .requireAccepted().signal());

        final Suspension<Resumed<String, String>> concurrent =
                ((FlowResult.Suspended<Resumed<String, String>>) first.run("state"))
                        .suspension();
        final List<Object> outcomes = Collections.synchronizedList(new ArrayList<Object>());
        Runnable resume = () -> {
            try { outcomes.add(first.resume(concurrent, point, "signal")); }
            catch (RuntimeException error) { outcomes.add(error); }
        };
        Thread left = new Thread(resume);
        Thread right = new Thread(resume);
        left.start();
        right.start();
        left.join();
        right.join();
        int flowResults = 0;
        int illegalStates = 0;
        for (Object item : outcomes) {
            if (item instanceof FlowResult) flowResults++;
            if (item instanceof IllegalStateException) illegalStates++;
        }
        assertEquals(1, flowResults);
        assertEquals(1, illegalStates);
    }

    @Test
    public void duplicateBranchesAndFailedBindingsAreDiagnosedOnce() {
        Branch<String, String> first = Branch.of("same", Flow.identity());
        Branch<String, String> second = Branch.of("same", Flow.identity());
        assertThrowsException(FlowBuildException.class,
                () -> Flow.parallel(first, second));

        final AtomicInteger resolutions = new AtomicInteger();
        OperationResolver resolver = (contract, qualifier) -> {
            resolutions.incrementAndGet();
            throw new IllegalStateException("missing");
        };
        assertThrowsException(FlowBuildException.class, () -> Local.compile(
                Flow.step(Missing.class).then(Missing.class), resolver));
        assertEquals(1, resolutions.get());
    }

    @Test
    public void publicContractsRejectNullAndBlankValues() {
        assertThrowsException(NullPointerException.class, () -> Outcome.rejected(null));
        assertThrowsException(NullPointerException.class, () -> Outcome.skipped(null));
        assertThrowsException(NullPointerException.class, () -> Outcome.failed(null));
        assertThrowsException(NullPointerException.class, () -> Outcome.accepted(null));
        assertThrowsException(NullPointerException.class,
                () -> new Reason(null, "message", Collections.emptyMap()));
        assertThrowsException(NullPointerException.class,
                () -> new Reason("CODE", null, Collections.emptyMap()));
        assertThrowsException(NullPointerException.class,
                () -> new Reason("CODE", "message", null));
        assertThrowsException(NullPointerException.class,
                () -> new Failure(null, "message", Collections.emptyMap()));
        assertThrowsException(NullPointerException.class,
                () -> new Failure("CODE", null, Collections.emptyMap()));
        assertThrowsException(NullPointerException.class,
                () -> new Failure("CODE", "message", null));
        assertThrowsException(NullPointerException.class,
                () -> Local.compile(Flow.<String>identity()).run(null));
        assertThrowsException(NullPointerException.class,
                () -> Flow.<String>identity().then((Flow<String, String>) null));
        assertThrowsException(IllegalArgumentException.class, () -> ResumePoint.named(" "));
        assertThrowsException(IllegalArgumentException.class,
                () -> Branch.of(" ", Flow.identity()));
        FlowResult.Completed<String> nullOutcome = (FlowResult.Completed<String>) Local.compile(
                Flow.step((Operation<String, String>) (context, input) -> null)).run("input");
        assertEquals("OPERATION_EXCEPTION",
                ((Outcome.Failed<String>) nullOutcome.outcome()).failure().code());

        ResumePoint<String> point = ResumePoint.named("nonnull-resume");
        LocalExecutable<String, Resumed<String, String>> awaiting =
                Local.compile(Flow.<String>identity().await(point));
        Suspension<Resumed<String, String>> suspension =
                ((FlowResult.Suspended<Resumed<String, String>>) awaiting.run("state"))
                        .suspension();
        assertThrowsException(NullPointerException.class,
                () -> awaiting.resume(suspension, point, null));
        assertEquals("signal", awaiting.resume(suspension, point, "signal")
                .requireAccepted().signal());

        Policy<String> policy = new Policy<String>() {
            @Override
            public Gate before(PolicyContext context, String key) {
                return Gate.proceed();
            }
        };
        FlowResult.Completed<String> nullKey = (FlowResult.Completed<String>) Local.compile(
                Flow.<String>identity().policy(policy, ignored -> null)).run("state");
        assertEquals("POLICY_EXCEPTION",
                ((Outcome.Failed<String>) nullKey.outcome()).failure().code());
        Policy<String> nullGate = new Policy<String>() {
            @Override
            public Gate before(PolicyContext context, String key) { return null; }
        };
        assertPolicyFailure(Flow.<String>identity().policy(nullGate, value -> value));

        PersistentPolicy<String, String> nullBefore = new PersistentPolicy<String, String>() {
            @Override
            public String initialState(String key) { return "state"; }
            @Override
            public Before<String> before(PolicyContext context, String key, String state) {
                return null;
            }
            @Override
            public After<String> after(PolicyContext context, String key, String state,
                                       Completion completion) {
                return PersistentPolicy.returning(state);
            }
        };
        assertPolicyFailure(Flow.<String>identity()
                .persistentPolicy(nullBefore, value -> value));

        PersistentPolicy<String, String> nullAfter = new PersistentPolicy<String, String>() {
            @Override
            public String initialState(String key) { return "state"; }
            @Override
            public Before<String> before(PolicyContext context, String key, String state) {
                return PersistentPolicy.proceed(state);
            }
            @Override
            public After<String> after(PolicyContext context, String key, String state,
                                       Completion completion) {
                return null;
            }
        };
        assertPolicyFailure(Flow.<String>identity()
                .persistentPolicy(nullAfter, value -> value));

        PersistentPolicy<String, String> nullState = new PersistentPolicy<String, String>() {
            @Override
            public String initialState(String key) { return null; }
            @Override
            public Before<String> before(PolicyContext context, String key, String state) {
                return PersistentPolicy.proceed(state);
            }
            @Override
            public After<String> after(PolicyContext context, String key, String state,
                                       Completion completion) {
                return PersistentPolicy.returning(state);
            }
        };
        FlowResult.Completed<String> invalidState =
                (FlowResult.Completed<String>) Local.compile(
                        Flow.<String>identity().persistentPolicy(
                                nullState, value -> value)).run("state");
        assertEquals("POLICY_EXCEPTION",
                ((Outcome.Failed<String>) invalidState.outcome()).failure().code());
    }

    private static void assertPolicyFailure(Flow<String, String> flow) {
        FlowResult.Completed<String> result =
                (FlowResult.Completed<String>) Local.compile(flow).run("state");
        assertEquals("POLICY_EXCEPTION",
                ((Outcome.Failed<String>) result.outcome()).failure().code());
    }

    private static void assertPersistentTerminal(final PersistentPolicy.Before<Integer> decision,
                                                 Outcome.Kind expected) {
        PersistentPolicy<String, Integer> policy = new PersistentPolicy<String, Integer>() {
            @Override
            public Integer initialState(String key) { return 0; }
            @Override
            public Before<Integer> before(PolicyContext context, String key, Integer state) {
                return decision;
            }
            @Override
            public After<Integer> after(PolicyContext context, String key, Integer state,
                                         Completion completion) {
                return PersistentPolicy.returning(state);
            }
        };
        FlowResult.Completed<String> result = (FlowResult.Completed<String>) Local.compile(
                Flow.<String>identity().persistentPolicy(policy, value -> value)).run("entry");
        assertEquals(expected, result.outcome().kind());
    }

    public static final class Missing implements Operation<String, String> {
        @Override
        public Outcome<String> execute(OperationContext context, String input) {
            return Outcome.accepted(input);
        }
    }

    private static <T extends Throwable> void assertThrowsException(Class<T> expectedType, Runnable executable) {
        try {
            executable.run();
            fail("Expected " + expectedType.getName() + " was not thrown");
        } catch (Throwable actual) {
            if (!expectedType.isInstance(actual)) {
                fail("Expected " + expectedType.getName() + " but was " + actual.getClass().getName());
            }
        }
    }
}
