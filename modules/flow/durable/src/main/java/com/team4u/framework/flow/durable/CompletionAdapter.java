package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.Completion;
import com.team4u.framework.flow.Outcome;

import java.util.Optional;

/**
 * Completion.from 在 Core 中是包私有的；这里通过公开构造器重建等价摘要。
 */
final class CompletionAdapter {
    private CompletionAdapter() {
    }

    static Completion from(Outcome<?> outcome) {
        if (outcome instanceof Outcome.Accepted) {
            return new Completion(Outcome.Kind.ACCEPTED,
                    Optional.<com.team4u.framework.flow.Reason>empty(),
                    Optional.<com.team4u.framework.flow.Failure>empty());
        }
        if (outcome instanceof Outcome.Rejected) {
            return new Completion(Outcome.Kind.REJECTED,
                    Optional.of(((Outcome.Rejected<?>) outcome).reason()),
                    Optional.<com.team4u.framework.flow.Failure>empty());
        }
        if (outcome instanceof Outcome.Skipped) {
            return new Completion(Outcome.Kind.SKIPPED,
                    Optional.of(((Outcome.Skipped<?>) outcome).reason()),
                    Optional.<com.team4u.framework.flow.Failure>empty());
        }
        if (outcome instanceof Outcome.Failed) {
            return new Completion(Outcome.Kind.FAILED,
                    Optional.<com.team4u.framework.flow.Reason>empty(),
                    Optional.of(((Outcome.Failed<?>) outcome).failure()));
        }
        throw new IllegalStateException("Unknown outcome: " + outcome);
    }
}
