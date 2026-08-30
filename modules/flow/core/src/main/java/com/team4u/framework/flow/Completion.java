package com.team4u.framework.flow;

import java.util.Objects;
import java.util.Optional;

/**
 * 不携带输出值的结果摘要，作为 Policy.before/after 的完成信息传入。
 * reason 与 outcome kind 须匹配：REJECTED/SKIPPED 必须带 reason，FAILED 必须带 failure。
 */
public final class Completion {
    private final Outcome.Kind kind;
    private final Optional<Reason> reason;
    private final Optional<Failure> failure;

    public Completion(Outcome.Kind kind, Optional<Reason> reason, Optional<Failure> failure) {
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        this.failure = Objects.requireNonNull(failure, "failure must not be null");
        if ((kind == Outcome.Kind.REJECTED || kind == Outcome.Kind.SKIPPED) != reason.isPresent()) {
            throw new IllegalArgumentException("reason does not match outcome kind");
        }
        if ((kind == Outcome.Kind.FAILED) != failure.isPresent()) {
            throw new IllegalArgumentException("failure does not match outcome kind");
        }
    }

    /** 丢弃 Outcome 的输出值，仅保留 kind/reason/failure 以供 Policy 评估。 */
    static Completion from(Outcome<?> outcome) {
        if (outcome instanceof Outcome.Accepted) {
            return new Completion(Outcome.Kind.ACCEPTED, Optional.empty(), Optional.empty());
        } else if (outcome instanceof Outcome.Rejected) {
            return new Completion(Outcome.Kind.REJECTED, Optional.of(((Outcome.Rejected<?>) outcome).reason()), Optional.empty());
        } else if (outcome instanceof Outcome.Skipped) {
            return new Completion(Outcome.Kind.SKIPPED, Optional.of(((Outcome.Skipped<?>) outcome).reason()), Optional.empty());
        } else if (outcome instanceof Outcome.Failed) {
            return new Completion(Outcome.Kind.FAILED, Optional.empty(), Optional.of(((Outcome.Failed<?>) outcome).failure()));
        }
        throw new IllegalStateException("Unknown outcome: " + outcome);
    }

    public Outcome.Kind kind() {
        return kind;
    }

    public Optional<Reason> reason() {
        return reason;
    }

    public Optional<Failure> failure() {
        return failure;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Completion that = (Completion) o;
        return kind == that.kind && reason.equals(that.reason) && failure.equals(that.failure);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, reason, failure);
    }

    @Override
    public String toString() {
        return "Completion[kind=" + kind + ", reason=" + reason + ", failure=" + failure + "]";
    }
}
