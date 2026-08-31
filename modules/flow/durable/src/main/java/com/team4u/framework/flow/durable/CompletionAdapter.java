package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.Completion;
import com.team4u.framework.flow.Outcome;

import java.util.Optional;

/**
 * 步骤完成摘要（{@link Completion}）适配构造器。
 *
 * <p>根据业务四态结果（{@link Outcome}）安全构造供 Policy 回调使用的只读摘要信息。</p>
 *
 * @author jay.wu
 */
final class CompletionAdapter {
    private CompletionAdapter() {
    }

    /**
     * 将 Outcome 转译为 Completion 摘要对象。
     *
     * @param outcome 业务四态结果，不能为 null
     * @return Completion 摘要
     */
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

