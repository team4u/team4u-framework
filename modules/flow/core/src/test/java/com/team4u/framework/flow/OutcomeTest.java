package com.team4u.framework.flow;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import com.team4u.framework.flow.model.Failure;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Reason;

/**
 * 契约：仅 Accepted 携带输出值，Rejected/Skipped 携带 Reason，Failed 携带 Failure；
 * map 仅作用于 Accepted，其余原样透传。
 */
public class OutcomeTest {

    @Test
    public void onlyAcceptedCarriesAValueAndMapPreservesDiagnostics() {
        Outcome<String> accepted = Outcome.accepted("ok");
        Outcome<String> mapped = accepted.map(value -> value + "+mapped");
        assertTrue(mapped instanceof Outcome.Accepted);
        assertEquals("ok+mapped", ((Outcome.Accepted<String>) mapped).value());

        Reason reason = Reason.of("R1", "rejected");
        Outcome<String> rejected = Outcome.rejected(reason);
        Outcome<Integer> rejectedMapped = rejected.map(String::length);
        assertTrue(rejectedMapped instanceof Outcome.Rejected);
        assertSame(reason, ((Outcome.Rejected<Integer>) rejectedMapped).reason());

        Reason skippedReason = Reason.of("S1", "skipped");
        Outcome<String> skipped = Outcome.skipped(skippedReason);
        Outcome<Integer> skippedMapped = skipped.map(String::length);
        assertTrue(skippedMapped instanceof Outcome.Skipped);
        assertSame(skippedReason, ((Outcome.Skipped<Integer>) skippedMapped).reason());

        Failure failure = Failure.of("F1", "failed");
        Outcome<String> failed = Outcome.failed(failure);
        Outcome<Integer> failedMapped = failed.map(String::length);
        assertTrue(failedMapped instanceof Outcome.Failed);
        assertSame(failure, ((Outcome.Failed<Integer>) failedMapped).failure());
    }

    @Test
    public void acceptedRejectsNull() {
        try {
            Outcome.accepted(null);
            fail();
        } catch (NullPointerException expected) {
            assertTrue(expected.getMessage().contains("accepted"));
        }
    }

    @Test
    public void rejectedRejectsNull() {
        try {
            Outcome.rejected(null);
            fail();
        } catch (NullPointerException expected) {
            assertTrue(expected.getMessage().contains("reason"));
        }
    }

    @Test
    public void skippedRejectsNull() {
        try {
            Outcome.skipped(null);
            fail();
        } catch (NullPointerException expected) {
            assertTrue(expected.getMessage().contains("reason"));
        }
    }

    @Test
    public void failedRejectsNull() {
        try {
            Outcome.failed(null);
            fail();
        } catch (NullPointerException expected) {
            assertTrue(expected.getMessage().contains("failure"));
        }
    }

    @Test
    public void testOutcomeAndFlowResultPredicates() {
        Outcome<String> accepted = Outcome.accepted("ok");
        assertTrue(accepted.isAccepted());
        org.junit.Assert.assertFalse(accepted.isRejected());
        org.junit.Assert.assertFalse(accepted.isSkipped());
        org.junit.Assert.assertFalse(accepted.isFailed());

        Outcome<String> rejected = Outcome.rejected(Reason.of("R", "rejected"));
        org.junit.Assert.assertFalse(rejected.isAccepted());
        assertTrue(rejected.isRejected());

        Outcome<String> skipped = Outcome.skipped(Reason.of("S", "skipped"));
        org.junit.Assert.assertFalse(skipped.isAccepted());
        assertTrue(skipped.isSkipped());

        Outcome<String> failed = Outcome.failed(Failure.of("F", "failed"));
        org.junit.Assert.assertFalse(failed.isAccepted());
        assertTrue(failed.isFailed());

        com.team4u.framework.flow.model.FlowResult<String> completedResult = com.team4u.framework.flow.model.FlowResult.completed(accepted);
        assertTrue(completedResult.isCompleted());
        org.junit.Assert.assertFalse(completedResult.isSuspended());
        org.junit.Assert.assertFalse(completedResult.isCancelled());
        assertTrue(completedResult.isAccepted());
        assertSame(accepted, completedResult.outcome());
        assertEquals("ok", completedResult.requireAccepted());

        com.team4u.framework.flow.model.FlowResult<String> cancelledResult = com.team4u.framework.flow.model.FlowResult.cancelled("id-1");
        org.junit.Assert.assertFalse(cancelledResult.isCompleted());
        assertTrue(cancelledResult.isCancelled());
        org.junit.Assert.assertFalse(cancelledResult.isAccepted());
        org.junit.Assert.assertNull(cancelledResult.outcome());
    }
}
