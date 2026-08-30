package com.team4u.framework.flow;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
}
