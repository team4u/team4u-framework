package com.team4u.framework.flow;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Flow 核心构造与运行时行为的基础验证：类型化 sequence/use/route/recoverWith，
 * 四态控制流分支，以及 Operation 解析、深度 scope 栈等编译期/运行期契约。
 */
public class FlowCoreTest {

    static final class State {
        private final String text;
        private final int length;

        public State(String text, int length) {
            this.text = text;
            this.length = length;
        }

        public String text() { return text; }
        public int length() { return length; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            State state = (State) o;
            return length == state.length && Objects.equals(text, state.text);
        }

        @Override
        public int hashCode() {
            return Objects.hash(text, length);
        }
    }

    @Test
    public void typedSequenceUseRouteAndRecovery() {
        Operation<String, Integer> length = (context, value) -> Outcome.accepted(value.length());
        Operation<Integer, Integer> doubleValue = (context, value) -> Outcome.accepted(value * 2);
        Flow<String, Integer> sequence = Flow.step(length).then(doubleValue);
        assertEquals(Integer.valueOf(8), Local.compile(sequence).run("flow").requireAccepted());

        Flow<State, State> projected = Flow.<State>identity().use(length,
                State::text, (state, result) -> new State(state.text(), result));
        assertEquals(new State("typed", 5),
                Local.compile(projected).run(new State("typed", 0)).requireAccepted());

        Flow<String, String> routed = Flow.route(
                        (Operation<String, Integer>) (context, value) ->
                                Outcome.accepted(value.length()))
                .caseOf(3, Flow.accepted("three"))
                .caseOf(4, Flow.accepted("four"))
                .otherwise(Flow.accepted("other"));
        assertEquals("four", Local.compile(routed).run("test").requireAccepted());

        final AtomicInteger fallbackCalls = new AtomicInteger();
        Flow<String, Integer> recovered = Flow.<String, Integer>failed(
                        Failure.of("BROKEN", "broken"))
                .recoverWith(Flow.step((context, recovery) -> {
                    fallbackCalls.incrementAndGet();
                    assertEquals("input", recovery.input());
                    assertEquals("BROKEN", recovery.failure().code());
                    return Outcome.accepted(7);
                }));
        assertEquals(Integer.valueOf(7), Local.compile(recovered).run("input").requireAccepted());
        assertEquals(1, fallbackCalls.get());
    }

    @Test
    public void fourStatesHaveDistinctControlFlow() {
        Reason no = Reason.of("NO", "not applicable");
        final AtomicInteger second = new AtomicInteger();
        Flow<String, Integer> applicable = Flow.firstApplicable(
                Flow.skipped(no),
                Flow.step((context, input) -> {
                    second.incrementAndGet();
                    return Outcome.accepted(input.length());
                }));
        assertEquals(Integer.valueOf(3), Local.compile(applicable).run("abc").requireAccepted());
        assertEquals(1, second.get());

        FlowResult<Integer> rejected = Local.compile(Flow.<String, Integer>rejected(no))
                .run("abc");
        assertTrue(((FlowResult.Completed<Integer>) rejected).outcome()
                instanceof Outcome.Rejected<?>);

        FlowResult<Integer> failed = Local.compile(Flow.<String, Integer>failed(
                Failure.of("X", "x"))).run("abc");
        assertTrue(((FlowResult.Completed<Integer>) failed).outcome()
                instanceof Outcome.Failed<?>);
    }

    @Test
    public void classAndQualifierAreResolvedOnceAndDescribed() {
        final AtomicInteger resolutions = new AtomicInteger();
        final List<FlowObserver.Event> events = new ArrayList<FlowObserver.Event>();
        OperationResolver resolver = new OperationResolver() {
            @Override public Object resolve(Class<?> contract, String qualifier) {
                resolutions.incrementAndGet();
                assertEquals("primary", qualifier);
                return new Upper();
            }

            @Override public Class<?> implementationClass(Object resolved) {
                return Upper.class;
            }
        };
        Flow<String, String> flow = Flow.step(Upper.class, "primary")
                .then(Upper.class, "primary");
        LocalExecutable<String, String> executable = Local.compile(flow, resolver, events::add);
        assertEquals(1, resolutions.get());
        assertEquals("ABC", executable.run("abc").requireAccepted());
        FlowObserver.Event invoke = null;
        for (FlowObserver.Event event : events) {
            if (event.descriptor().kind() == NodeDescriptor.Kind.INVOKE) {
                invoke = event;
                break;
            }
        }
        assertTrue(invoke != null);
        assertEquals(Upper.class, invoke.descriptor().contractClass().get());
        assertEquals(Upper.class, invoke.descriptor().implementationClass().get());
        assertEquals("primary", invoke.descriptor().qualifier().get());
    }

    @Test
    public void deepExplicitScopesUseCompilerAndMachineStacks() {
        Flow<Integer, Integer> flow = Flow.identity();
        for (int index = 0; index < 5000; index++)
            flow = Flow.scope("scope-" + index, flow);
        assertEquals(Integer.valueOf(7), Local.compile(flow).run(7).requireAccepted());
    }

    public static final class Upper implements Operation<String, String> {
        @Override public Outcome<String> execute(OperationContext context, String input) {
            return Outcome.accepted(input.toUpperCase());
        }
    }
}
