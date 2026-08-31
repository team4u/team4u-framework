package com.team4u.framework.flow.test;

import com.team4u.framework.flow.api.PersistentPolicy;
import com.team4u.framework.flow.api.PolicyContext;
import com.team4u.framework.flow.model.Completion;
import com.team4u.framework.flow.model.Outcome;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 固定次数重试的 {@link PersistentPolicy} 测试桩（Counting Persistent Policy Stub）。
 *
 * <p>以不可变 {@link Integer}（当前轮次，从 1 起计）为策略状态：目标步骤 Failed 且未达到
 * {@code maxAttempts} 时按 {@code retryAt(now + backoff)} 退避重试；其余情形（Accepted/Rejected/
 * Skipped 或次数耗尽）直接返回当前状态。适合在测试中快速搭建"失败 N 次后成功/耗尽"的编排。</p>
 *
 * @param <K> 策略键类型
 * @author jay.wu
 */
public final class PersistentPolicyStub<K> implements PersistentPolicy<K, Integer> {

    private final int maxAttempts;
    private final Duration backoff;

    private PersistentPolicyStub(int maxAttempts, Duration backoff) {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be greater than 0");
        }
        this.maxAttempts = maxAttempts;
        this.backoff = Objects.requireNonNull(backoff, "backoff must not be null");
    }

    /**
     * 创建固定次数与固定退避的重试策略桩。
     *
     * @param maxAttempts 最大尝试次数（包含初试，>= 1）
     * @param backoff     每次重试前的退避时长
     * @param <K>         策略键类型
     * @return 重试策略桩实例
     */
    public static <K> PersistentPolicyStub<K> counting(int maxAttempts, Duration backoff) {
        return new PersistentPolicyStub<K>(maxAttempts, backoff);
    }

    @Override
    public Integer initialState(K key) {
        return 1;
    }

    @Override
    public Before<Integer> before(PolicyContext context, K key, Integer state) {
        return PersistentPolicy.proceed(state);
    }

    @Override
    public After<Integer> after(PolicyContext context, K key, Integer state, Completion completion) {
        Integer current = state != null ? state : 1;
        if (completion != null
                && completion.kind() == Outcome.Kind.FAILED
                && current < maxAttempts) {
            return PersistentPolicy.retryAt(Instant.now().plus(backoff), current + 1);
        }
        return PersistentPolicy.returning(current);
    }
}
