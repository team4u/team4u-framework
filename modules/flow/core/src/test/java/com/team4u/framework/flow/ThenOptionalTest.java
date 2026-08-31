package com.team4u.framework.flow;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import com.team4u.framework.flow.api.FlowObserver;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.model.Failure;
import com.team4u.framework.flow.model.FlowResult;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Reason;
import com.team4u.framework.flow.spi.NodeDescriptor;
import com.team4u.framework.flow.spi.OperationResolver;

/**
 * thenOptional（可选步骤）行为矩阵验证：Skipped 原值透传继续、Accepted 新值推进、
 * Rejected/Failed 照常短路、普通 then 的 Skipped 语义不被改变、class/qualifier 延迟解析路径，
 * 以及可选子流程整体回退到入口值（而非内部中间值）与 FALLBACK_SELECTED 观测事件。
 */
public class ThenOptionalTest {

    @Test
    public void skippedPassesOriginalValueToNextNode() {
        final AtomicInteger skippedCalls = new AtomicInteger();
        final List<String> nextInputs = new ArrayList<String>();
        Flow<String, String> flow = Flow.<String>identity()
                .thenOptional((context, input) -> {
                    skippedCalls.incrementAndGet();
                    return Outcome.skipped(Reason.of("NO_HANDLER", "no handler"));
                })
                .then((context, input) -> {
                    nextInputs.add(input);
                    return Outcome.accepted(input + "-after");
                });
        assertEquals("entry-after", Local.compile(flow).run("entry").requireAccepted());
        assertEquals(1, skippedCalls.get());
        assertEquals("Skipped 后续节点拿到的是进入该步骤时的原值",
                "entry", nextInputs.get(0));
    }

    @Test
    public void acceptedFeedsNewValueToNextNode() {
        final List<String> nextInputs = new ArrayList<String>();
        Flow<String, String> flow = Flow.<String>identity()
                .thenOptional((context, input) -> Outcome.accepted(input.toUpperCase()))
                .then((context, input) -> {
                    nextInputs.add(input);
                    return Outcome.accepted(input + "-after");
                });
        assertEquals("ENTRY-after", Local.compile(flow).run("entry").requireAccepted());
        assertEquals("ENTRY", nextInputs.get(0));
    }

    @Test
    public void rejectedAndFailedStillShortCircuit() {
        Reason no = Reason.of("NO", "no");
        Failure failure = Failure.of("BROKEN", "broken");
        List<Outcome<String>> terminal = java.util.Arrays.asList(
                Outcome.rejected(no), Outcome.failed(failure));
        for (Outcome<String> outcome : terminal) {
            final AtomicInteger next = new AtomicInteger();
            Flow<String, String> flow = Flow.<String>identity()
                    .thenOptional((context, input) -> outcome)
                    .then((context, input) -> {
                        next.incrementAndGet();
                        return Outcome.accepted(input);
                    });
            FlowResult.Completed<String> result =
                    (FlowResult.Completed<String>) Local.compile(flow).run("entry");
            assertEquals("thenOptional 不得吞掉 " + outcome.kind(),
                    outcome.kind(), result.outcome().kind());
            assertEquals("后续节点不得执行", 0, next.get());
        }
    }

    @Test
    public void plainThenStillShortCircuitsOnSkipped() {
        final AtomicInteger next = new AtomicInteger();
        Flow<String, String> flow = Flow.<String>identity()
                .then((Operation<String, String>) (context, input) ->
                        Outcome.skipped(Reason.of("SKIP", "skip")))
                .then((context, input) -> {
                    next.incrementAndGet();
                    return Outcome.accepted(input);
                });
        FlowResult<String> result = Local.compile(flow).run("entry");
        assertTrue(((FlowResult.Completed<String>) result).outcome()
                instanceof Outcome.Skipped<?>);
        assertEquals(0, next.get());
    }

    @Test
    public void classAndQualifierOverloadsResolveAndBehave() {
        final AtomicInteger resolutions = new AtomicInteger();
        OperationResolver resolver = new OperationResolver() {
            @Override public Object resolve(Class<?> contract, String qualifier) {
                resolutions.incrementAndGet();
                if (contract == MaybeUpper.class) {
                    assertEquals("primary", qualifier);
                    return new MaybeUpper();
                }
                if (contract == Lower.class) {
                    assertEquals(null, qualifier);
                    return new Lower();
                }
                throw new AssertionError("Unexpected contract: " + contract.getName());
            }

            @Override public Class<?> implementationClass(Object resolved) {
                return resolved.getClass();
            }
        };
        Flow<String, String> flow = Flow.<String>identity()
                .thenOptional(MaybeUpper.class, "primary")
                .thenOptional(Lower.class);
        LocalExecutable<String, String> executable = Local.compile(flow, resolver);
        assertEquals(2, resolutions.get());
        // MaybeUpper 弃权 → 原值透传；Lower 接受 → 小写新值
        assertEquals("abc", executable.run("ABC").requireAccepted());
        assertEquals(2, resolutions.get());
    }

    public static final class MaybeUpper implements Operation<String, String> {
        @Override public Outcome<String> execute(OperationContext context, String input) {
            return Outcome.skipped(Reason.of("NO_OP", "no op"));
        }
    }

    public static final class Lower implements Operation<String, String> {
        @Override public Outcome<String> execute(OperationContext context, String input) {
            return Outcome.accepted(input.toLowerCase());
        }
    }

    @Test
    public void optionalSubflowFallsBackToEntryValueNotIntermediate() {
        // 子流程内部：第一步 Accepted 产出中间值，第二步 Skipped 弃权
        Flow<String, String> subflow = Flow.<String, String>step(
                        (Operation<String, String>) (context, input) ->
                                Outcome.accepted(input + "-intermediate"))
                .then((context, input) -> Outcome.skipped(Reason.of("NA", "na")));
        Flow<String, String> flow = Flow.<String>identity()
                .thenOptional(subflow)
                .then((context, input) -> Outcome.accepted(input + "-after"));
        assertEquals("整体 Skipped 回退到子流程入口值，而非中间值",
                "entry-after", Local.compile(flow).run("entry").requireAccepted());
    }

    @Test
    public void optionalSubflowAcceptedUsesFinalOutput() {
        Flow<String, String> subflow = Flow.<String, String>step(
                        (Operation<String, String>) (context, input) ->
                                Outcome.accepted(input + "-first"))
                .then((context, input) -> Outcome.accepted(input + "-second"));
        Flow<String, String> flow = Flow.<String>identity()
                .thenOptional(subflow)
                .then((context, input) -> Outcome.accepted("[" + input + "]"));
        assertEquals("[entry-first-second]",
                Local.compile(flow).run("entry").requireAccepted());
    }

    @Test
    public void skippedIsObservableThroughFallbackAndNodeEvents() {
        final List<FlowObserver.Event> events = new ArrayList<FlowObserver.Event>();
        Flow<String, String> flow = Flow.<String>identity()
                .thenOptional((context, input) ->
                        Outcome.skipped(Reason.of("NA", "not applicable")));
        assertEquals("entry", Local.compile(flow,
                OperationResolver.rejecting(), events::add).run("entry").requireAccepted());

        boolean fallbackSelected = false;
        int skippedInvokes = 0;
        int acceptedCompletes = 0;
        for (FlowObserver.Event event : events) {
            if (event.type() == FlowObserver.Type.FALLBACK_SELECTED) {
                fallbackSelected = true;
            }
            if (event.type() == FlowObserver.Type.NODE_COMPLETED
                    && event.descriptor().kind() == NodeDescriptor.Kind.INVOKE
                    && "SKIPPED".equals(event.attributes().get("outcome"))) {
                skippedInvokes++;
            }
            if (event.type() == FlowObserver.Type.NODE_COMPLETED
                    && "ACCEPTED".equals(event.attributes().get("outcome"))) {
                acceptedCompletes++;
            }
        }
        assertTrue("FALLBACK_SELECTED 事件必须上报", fallbackSelected);
        assertEquals("弃权步骤的 Skipped 不得被静默吞掉", 1, skippedInvokes);
        assertTrue("identity 兜底分支应产生 Accepted 完成事件", acceptedCompletes >= 1);
    }

    @Test
    public void thenOptionalChainsEveryNodeExecutes() {
        // 全链可选：每个节点都执行，最终拿到逐步透传/更新的值
        final List<String> executed = new ArrayList<String>();
        Flow<String, String> flow = Flow.<String>identity()
                .thenOptional((context, input) -> {
                    executed.add("a");
                    return Outcome.skipped(Reason.of("A", "a"));
                })
                .thenOptional((context, input) -> {
                    executed.add("b");
                    return Outcome.accepted(input + "-b");
                })
                .thenOptional((context, input) -> {
                    executed.add("c");
                    return Outcome.skipped(Reason.of("C", "c"));
                });
        assertEquals("entry-b", Local.compile(flow).run("entry").requireAccepted());
        assertEquals(java.util.Arrays.asList("a", "b", "c"), executed);
    }

    @Test
    public void thenOptionalRejectsNullArguments() {
        assertThrows(new Runnable() {
            @Override public void run() {
                Flow.<String>identity().thenOptional((Operation<String, String>) null);
            }
        }, NullPointerException.class);
        assertThrows(new Runnable() {
            @Override public void run() {
                Flow.<String>identity().thenOptional((Class<Operation<String, String>>) null);
            }
        }, NullPointerException.class);
        assertThrows(new Runnable() {
            @Override public void run() {
                Flow.<String>identity().thenOptional((Flow<String, String>) null);
            }
        }, NullPointerException.class);
    }

    private static void assertThrows(Runnable executable, Class<? extends Throwable> expected) {
        try {
            executable.run();
            throw new AssertionError("Expected " + expected.getName() + " was not thrown");
        } catch (Throwable actual) {
            if (!expected.isInstance(actual)) {
                throw new AssertionError("Expected " + expected.getName()
                        + " but was " + actual.getClass().getName());
            }
        }
    }
}
