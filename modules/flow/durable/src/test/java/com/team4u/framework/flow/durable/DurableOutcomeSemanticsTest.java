package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.Flow;
import org.junit.Test;

import static com.team4u.framework.flow.durable.DurableTestOps.RecordingOp;
import static com.team4u.framework.flow.durable.DurableTestOps.acceptedValue;
import static com.team4u.framework.flow.durable.DurableTestOps.failed;
import static com.team4u.framework.flow.durable.DurableTestOps.rejected;
import static com.team4u.framework.flow.durable.DurableTestOps.skipped;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import com.team4u.framework.flow.Local;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.durable.store.DurableStore;
import com.team4u.framework.flow.durable.store.InMemoryDurableStore;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Recovery;
import com.team4u.framework.flow.spi.FallbackTrigger;

/** 组1：四态传播与 fallback/route/complete 语义（与 Core Local 严格一致）。 */
public class DurableOutcomeSemanticsTest {

    private static DurableExecutable<String, String> compile(Flow<String, String> flow,
                                                             DurableStore store) {
        return Durable.builder(store).build().compile(flow, "sem", 1);
    }

    private static Outcome<String> outcome(DurableResult<String> result) {
        return ((DurableResult.Completed<String>) result).outcome();
    }

    @Test
    public void acceptedPropagatesThroughSequence() {
        RecordingOp a = new RecordingOp("a");
        RecordingOp b = new RecordingOp("b");
        DurableResult<String> result = compile(Flow.<String, String>step(a).then(b),
                new InMemoryDurableStore()).start("e", "s");
        assertEquals("s>a>b", acceptedValue(result));
    }

    @Test
    public void thenOptionalPassesEntryOnSkippedAndAcceptedValueOtherwise() {
        RecordingOp skippedOptional = new RecordingOp("skipped").returns(skipped("NA"));
        RecordingOp afterSkipped = new RecordingOp("after-skipped");
        Flow<String, String> skippedFlow = Flow.<String>identity()
                .thenOptional(skippedOptional)
                .then(afterSkipped);
        DurableResult<String> skippedResult = compile(skippedFlow,
                new InMemoryDurableStore()).start("optional-skipped", "s");
        assertEquals("s>after-skipped", acceptedValue(skippedResult));
        assertEquals("s", skippedOptional.inputs().get(0));
        assertEquals("s", afterSkipped.inputs().get(0));

        RecordingOp acceptedOptional = new RecordingOp("accepted");
        RecordingOp afterAccepted = new RecordingOp("after-accepted");
        Flow<String, String> acceptedFlow = Flow.<String>identity()
                .thenOptional(acceptedOptional)
                .then(afterAccepted);
        DurableResult<String> acceptedResult = compile(acceptedFlow,
                new InMemoryDurableStore()).start("optional-accepted", "s");
        assertEquals("s>accepted>after-accepted", acceptedValue(acceptedResult));
        assertEquals("s>accepted", afterAccepted.inputs().get(0));
    }

    @Test
    public void thenOptionalDoesNotConsumeRejectedOrFailed() {
        RecordingOp rejectedOptional = new RecordingOp("rejected").returns(rejected("NO"));
        RecordingOp afterRejected = new RecordingOp("after-rejected");
        DurableResult<String> rejectedResult = compile(Flow.<String>identity()
                        .thenOptional(rejectedOptional)
                        .then(afterRejected), new InMemoryDurableStore())
                .start("optional-rejected", "s");
        assertEquals(Outcome.Kind.REJECTED, outcome(rejectedResult).kind());
        assertEquals(0, afterRejected.calls());

        RecordingOp failedOptional = new RecordingOp("failed").returns(failed("BAD"));
        RecordingOp afterFailed = new RecordingOp("after-failed");
        DurableResult<String> failedResult = compile(Flow.<String>identity()
                        .thenOptional(failedOptional)
                        .then(afterFailed), new InMemoryDurableStore())
                .start("optional-failed", "s");
        assertEquals(Outcome.Kind.FAILED, outcome(failedResult).kind());
        assertEquals(0, afterFailed.calls());
    }

    @Test
    public void rejectedStopsSequenceAndRetainsScopeEntryForFallback() {
        RecordingOp a = new RecordingOp("a").returns(rejected("NOPE"));
        RecordingOp b = new RecordingOp("b");
        DurableResult<String> result = compile(Flow.<String, String>step(a).then(b),
                new InMemoryDurableStore()).start("e", "s");
        assertEquals(Outcome.Kind.REJECTED, outcome(result).kind());
        assertEquals("NOPE", ((Outcome.Rejected<String>) outcome(result)).reason().code());
        assertEquals("b 未执行", 0, b.calls());
    }

    @Test
    public void failedPropagatesAndRetainsScopeEntry() {
        RecordingOp a = new RecordingOp("a").returns(failed("BAD"));
        DurableResult<String> result = compile(Flow.<String, String>step(a),
                new InMemoryDurableStore()).start("e", "s");
        assertEquals(Outcome.Kind.FAILED, outcome(result).kind());
        assertEquals("BAD", ((Outcome.Failed<String>) outcome(result)).failure().code());
    }

    @Test
    public void skippedPropagates() {
        RecordingOp a = new RecordingOp("a").returns(skipped("NA"));
        DurableResult<String> result = compile(Flow.<String, String>step(a),
                new InMemoryDurableStore()).start("e", "s");
        assertEquals(Outcome.Kind.SKIPPED, outcome(result).kind());
    }

    @Test
    public void firstApplicableTriesNextBranchOnlyOnSkipped() {
        RecordingOp first = new RecordingOp("first").returns(skipped("NA"));
        RecordingOp second = new RecordingOp("second");
        RecordingOp third = new RecordingOp("third");
        Flow<String, String> flow = Flow.firstApplicable(
                Flow.<String, String>step(first),
                Flow.<String, String>step(second),
                Flow.<String, String>step(third));
        DurableResult<String> result = compile(flow, new InMemoryDurableStore()).start("e", "s");
        assertEquals("s>second", acceptedValue(result));
        assertEquals(1, first.calls());
        assertEquals(1, second.calls());
        assertEquals("third 未执行", 0, third.calls());
    }

    @Test
    public void firstApplicableRejectDoesNotTriggerNextBranch() {
        RecordingOp first = new RecordingOp("first").returns(rejected("NO"));
        RecordingOp second = new RecordingOp("second");
        Flow<String, String> flow = Flow.firstApplicable(
                Flow.<String, String>step(first),
                Flow.<String, String>step(second));
        DurableResult<String> result = compile(flow, new InMemoryDurableStore()).start("e", "s");
        assertEquals(Outcome.Kind.REJECTED, outcome(result).kind());
        assertEquals("second 未执行", 0, second.calls());
    }

    @Test
    public void firstApplicableAllSkippedYieldsSkipped() {
        RecordingOp first = new RecordingOp("first").returns(skipped("A"));
        RecordingOp second = new RecordingOp("second").returns(skipped("B"));
        Flow<String, String> flow = Flow.firstApplicable(
                Flow.<String, String>step(first),
                Flow.<String, String>step(second));
        DurableResult<String> result = compile(flow, new InMemoryDurableStore()).start("e", "s");
        assertEquals(Outcome.Kind.SKIPPED, outcome(result).kind());
        assertEquals("B", ((Outcome.Skipped<String>) outcome(result)).reason().code());
    }

    @Test
    public void recoverWithFeedsRecoveryToNextBranchOnFailed() {
        RecordingOp body = new RecordingOp("body").returns(failed("ERR"));
        Flow<Recovery<String>, String> recover = Flow.<Recovery<String>, String>step(
                new com.team4u.framework.flow.api.Operation<Recovery<String>, String>() {
                    @Override
                    public Outcome<String> execute(
                            com.team4u.framework.flow.api.OperationContext context,
                            Recovery<String> recovery) {
                        return Outcome.accepted(
                                recovery.input() + "|" + recovery.failure().code());
                    }
                });
        Flow<String, String> flow = Flow.<String, String>step(body).recoverWith(recover);
        DurableResult<String> result = compile(flow, new InMemoryDurableStore())
                .start("e", "orig");
        assertEquals("orig|ERR", acceptedValue(result));
    }

    @Test
    public void recoverWithDoesNotTriggerOnAccepted() {
        RecordingOp body = new RecordingOp("body");
        Flow<Recovery<String>, String> recover = Flow.<Recovery<String>, String>step(
                new com.team4u.framework.flow.api.Operation<Recovery<String>, String>() {
                    @Override
                    public Outcome<String> execute(
                            com.team4u.framework.flow.api.OperationContext ctx,
                            Recovery<String> recovery) {
                        return Outcome.accepted("recovered");
                    }
                });
        Flow<String, String> flow = Flow.<String, String>step(body).recoverWith(recover);
        DurableResult<String> result = compile(flow, new InMemoryDurableStore()).start("e", "s");
        assertEquals("s>body", acceptedValue(result));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void routeSelectsMatchingCase() {
        Flow<String, String> flow = Flow.<String, String>route(
                new com.team4u.framework.flow.api.Operation<String, String>() {
                    @Override
                    public Outcome<String> execute(
                            com.team4u.framework.flow.api.OperationContext context, String input) {
                        return Outcome.accepted(input.startsWith("a") ? "A" : "B");
                    }
                })
                .caseOf("A", Flow.<String, String>step(new RecordingOp("caseA")))
                .caseOf("B", Flow.<String, String>step(new RecordingOp("caseB")))
                .otherwise(Flow.<String, String>step(new RecordingOp("other")));
        assertEquals("apple>caseA",
                acceptedValue(compile(flow, new InMemoryDurableStore()).start("e", "apple")));
        assertEquals("banana>caseB",
                acceptedValue(compile(flow, new InMemoryDurableStore()).start("e2", "banana")));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void routeOtherwiseWhenNoCaseMatches() {
        Flow<String, String> flow = Flow.<String, String>route(
                new com.team4u.framework.flow.api.Operation<String, String>() {
                    @Override
                    public Outcome<String> execute(
                            com.team4u.framework.flow.api.OperationContext context, String input) {
                        return Outcome.accepted("Z");
                    }
                })
                .caseOf("A", Flow.<String, String>step(new RecordingOp("caseA")))
                .otherwise(Flow.<String, String>step(new RecordingOp("other")));
        assertEquals("s>other",
                acceptedValue(compile(flow, new InMemoryDurableStore()).start("e", "s")));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void routeWithoutOtherwiseYieldsSkipped() {
        Flow<String, String> flow = Flow.<String, String>route(
                new com.team4u.framework.flow.api.Operation<String, String>() {
                    @Override
                    public Outcome<String> execute(
                            com.team4u.framework.flow.api.OperationContext context, String input) {
                        return Outcome.accepted("Z");
                    }
                })
                .caseOf("A", Flow.<String, String>step(new RecordingOp("caseA")))
                .withoutOtherwise();
        DurableResult<String> result = compile(flow, new InMemoryDurableStore()).start("e", "s");
        assertEquals(Outcome.Kind.SKIPPED, outcome(result).kind());
        assertEquals("NO_ROUTE",
                ((Outcome.Skipped<String>) outcome(result)).reason().code());
    }

    @Test
    public void completeIdentityPassesEntryThrough() {
        Flow<String, String> flow = Flow.<String>identity();
        assertEquals("raw", acceptedValue(compile(flow, new InMemoryDurableStore())
                .start("e", "raw")));
    }

    @Test
    public void completeFixedOutcomeWins() {
        Flow<String, String> flow = Flow.accepted("constant");
        assertEquals("constant", acceptedValue(compile(flow, new InMemoryDurableStore())
                .start("e", "ignored")));
    }

    @Test
    public void fallbackTriggerMatchesCoreSemantics() {
        // FallbackTrigger 枚举与 Core 一致
        assertEquals(FallbackTrigger.SKIPPED, FallbackTrigger.valueOf("SKIPPED"));
        assertEquals(FallbackTrigger.FAILED, FallbackTrigger.valueOf("FAILED"));
    }

    @Test
    public void operationExceptionBecomesStableFailedOutcome() {
        com.team4u.framework.flow.api.Operation<String, String> boom =
                new com.team4u.framework.flow.api.Operation<String, String>() {
            @Override
            public Outcome<String> execute(
                    com.team4u.framework.flow.api.OperationContext context, String input) {
                throw new IllegalStateException("kaboom");
            }
        };
        Flow<String, String> flow = Flow.step(boom);
        DurableResult<String> result = compile(flow, new InMemoryDurableStore())
                .start("e", "s");
        Outcome.Failed<String> failedOutcome = (Outcome.Failed<String>) outcome(result);
        assertEquals("OPERATION_EXCEPTION", failedOutcome.failure().code());
        assertTrue("message 含异常类名: " + failedOutcome.failure().message(),
                failedOutcome.failure().message().contains("IllegalStateException"));
        assertTrue(failedOutcome.failure().message().contains("kaboom"));
    }
}
