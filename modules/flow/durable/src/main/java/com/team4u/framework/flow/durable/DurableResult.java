package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.Outcome;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Closed Java 8 result family for durable commands. */
public abstract class DurableResult<O> {
    private final DurableSnapshot snapshot;

    DurableResult(DurableSnapshot snapshot, DurableLifecycle expected) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (snapshot.lifecycle() != expected) {
            throw new IllegalArgumentException("result requires " + expected
                    + " snapshot, but was " + snapshot.lifecycle());
        }
    }

    public final DurableSnapshot snapshot() {
        return snapshot;
    }

    public O requireAccepted() {
        if (this instanceof Completed) {
            Outcome<O> outcome = ((Completed<O>) this).outcome();
            if (outcome instanceof Outcome.Accepted) {
                return ((Outcome.Accepted<O>) outcome).value();
            }
        }
        throw new IllegalStateException(
                "Durable execution did not complete with Accepted");
    }

    public static final class Completed<O> extends DurableResult<O> {
        private final Outcome<O> outcome;

        public Completed(Outcome<O> outcome, DurableSnapshot snapshot) {
            super(snapshot, DurableLifecycle.COMPLETED);
            this.outcome = Objects.requireNonNull(outcome, "outcome must not be null");
        }

        public Outcome<O> outcome() { return outcome; }
    }

    public static final class Suspended<O> extends DurableResult<O> {
        private final String resumePoint;

        public Suspended(String resumePoint, DurableSnapshot snapshot) {
            super(snapshot, DurableLifecycle.SUSPENDED);
            this.resumePoint = text(resumePoint);
            if (!resumePoint.equals(snapshot.awaitingPoint())) {
                throw new IllegalArgumentException(
                        "resumePoint must match snapshot awaitingPoint");
            }
        }

        public String resumePoint() { return resumePoint; }
    }

    public static final class Active<O> extends DurableResult<O> {
        private final Optional<Instant> wakeAt;

        public Active(Optional<Instant> wakeAt, DurableSnapshot snapshot) {
            super(snapshot, DurableLifecycle.ACTIVE);
            this.wakeAt = Objects.requireNonNull(wakeAt, "wakeAt must not be null");
        }

        public Optional<Instant> wakeAt() { return wakeAt; }
    }

    public static final class Cancelled<O> extends DurableResult<O> {
        public Cancelled(DurableSnapshot snapshot) {
            super(snapshot, DurableLifecycle.CANCELLED);
        }
    }

    private static String text(String value) {
        Objects.requireNonNull(value, "resumePoint must not be null");
        if (value.trim().isEmpty()) throw new IllegalArgumentException(
                "resumePoint must not be blank");
        return value;
    }
}
