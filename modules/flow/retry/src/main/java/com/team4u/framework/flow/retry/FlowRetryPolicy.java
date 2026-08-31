package com.team4u.framework.flow.retry;

import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.api.PersistentPolicy;
import com.team4u.framework.flow.api.PolicyContext;
import com.team4u.framework.flow.model.Completion;
import com.team4u.framework.flow.model.Failure;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.retry.api.NamedRetryPolicyFactory;
import com.team4u.framework.retry.api.NamedRetryPolicyRegistry;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.common.backoff.Backoff;
import com.team4u.framework.retry.common.backoff.Backoffs;
import lombok.Builder;
import lombok.Getter;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 基于 team4u-retry 与 {@link PersistentPolicy} 契约的流程有状态重试与退避治理策略。
 *
 * <p>架构对称性说明：
 * <ul>
 *   <li>无状态治理（如限流、鉴权）：基于 {@code Policy<K>}（如 team4u-flow-ratelimiter）；</li>
 *   <li>有状态治理（如重试、断点变迁）：基于 {@code PersistentPolicy<K, S>}（如 team4u-flow-retry），状态为不可变的 {@link FlowRetryState}；</li>
 *   <li>超时治理：基于 {@code Timeout} 作用域。</li>
 * </ul>
 * </p>
 *
 * @param <K> 策略路由键类型
 * @author jay.wu
 */
@Getter
public class FlowRetryPolicy<K> implements PersistentPolicy<K, FlowRetryState> {

    private final Integer maxAttempts;
    private final Backoff backoff;
    private final Predicate<Failure> retryPredicate;
    private final String policyName;
    private final NamedRetryPolicyRegistry namedRegistry;

    @Builder(toBuilder = true)
    public FlowRetryPolicy(
            Integer maxAttempts,
            Backoff backoff,
            Predicate<Failure> retryPredicate,
            String policyName,
            NamedRetryPolicyRegistry namedRegistry) {
        if (maxAttempts != null && maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be greater than 0");
        }
        this.maxAttempts = maxAttempts;
        this.backoff = backoff;
        this.retryPredicate = retryPredicate;
        this.policyName = policyName;
        this.namedRegistry = namedRegistry;
    }

    /**
     * 创建基于指定最大尝试次数与退避策略的重试策略。
     *
     * @param maxAttempts 最大尝试次数（包含初试，>= 1）
     * @param backoff     退避策略
     * @param <K>         键类型
     * @return FlowRetryPolicy 实例
     */
    public static <K> FlowRetryPolicy<K> of(int maxAttempts, Backoff backoff) {
        return FlowRetryPolicy.<K>builder()
                .maxAttempts(maxAttempts)
                .backoff(backoff)
                .build();
    }

    /**
     * 创建基于指定最大尝试次数、退避策略及失败判定谓词的重试策略。
     *
     * @param maxAttempts    最大尝试次数（包含初试，>= 1）
     * @param backoff        退避策略
     * @param retryPredicate 失败重试判定谓词
     * @param <K>            键类型
     * @return FlowRetryPolicy 实例
     */
    public static <K> FlowRetryPolicy<K> of(int maxAttempts, Backoff backoff, Predicate<Failure> retryPredicate) {
        return FlowRetryPolicy.<K>builder()
                .maxAttempts(maxAttempts)
                .backoff(backoff)
                .retryPredicate(retryPredicate)
                .build();
    }

    /**
     * 创建指数退避重试策略。
     *
     * @param maxAttempts        最大尝试次数（包含初试，>= 1）
     * @param initialDelayMillis 初始延迟毫秒数
     * @param multiplier         倍数
     * @param maxDelayMillis     最大延迟毫秒数
     * @param <K>                键类型
     * @return FlowRetryPolicy 实例
     */
    public static <K> FlowRetryPolicy<K> exponential(int maxAttempts, long initialDelayMillis, double multiplier, long maxDelayMillis) {
        return of(maxAttempts, Backoffs.exponential(initialDelayMillis, multiplier, maxDelayMillis));
    }

    /**
     * 创建带随机抖动的指数退避重试策略。
     *
     * @param maxAttempts        最大尝试次数（包含初试，>= 1）
     * @param initialDelayMillis 初始延迟毫秒数
     * @param multiplier         倍数
     * @param maxDelayMillis     最大延迟毫秒数
     * @param <K>                键类型
     * @return FlowRetryPolicy 实例
     */
    public static <K> FlowRetryPolicy<K> jitter(int maxAttempts, long initialDelayMillis, double multiplier, long maxDelayMillis) {
        return of(maxAttempts, Backoffs.exponentialJitter(initialDelayMillis, multiplier, maxDelayMillis));
    }

    /**
     * 创建固定延迟退避重试策略。
     *
     * @param maxAttempts 最大尝试次数（包含初试，>= 1）
     * @param delayMillis 延迟毫秒数
     * @param <K>         键类型
     * @return FlowRetryPolicy 实例
     */
    public static <K> FlowRetryPolicy<K> fixed(int maxAttempts, long delayMillis) {
        return of(maxAttempts, Backoffs.fixed(delayMillis));
    }

    /**
     * 创建递增延迟退避重试策略。
     *
     * @param maxAttempts          最大尝试次数（包含初试，>= 1）
     * @param initialDelayMillis   初始延迟毫秒数
     * @param incrementDelayMillis 递增延迟毫秒数
     * @param <K>                  键类型
     * @return FlowRetryPolicy 实例
     */
    public static <K> FlowRetryPolicy<K> increment(int maxAttempts, long initialDelayMillis, long incrementDelayMillis) {
        return of(maxAttempts, Backoffs.increment(initialDelayMillis, incrementDelayMillis));
    }

    /**
     * 创建基于动态/命名规则的重试策略。
     *
     * @param policyName 策略名称
     * @param <K>        键类型
     * @return FlowRetryPolicy 实例
     */
    public static <K> FlowRetryPolicy<K> named(String policyName) {
        return FlowRetryPolicy.<K>builder()
                .policyName(policyName)
                .build();
    }

    /**
     * 创建基于指定命名注册表与策略名称的重试策略。
     *
     * @param registry   命名策略注册表
     * @param policyName 策略名称
     * @param <K>        键类型
     * @return FlowRetryPolicy 实例
     */
    public static <K> FlowRetryPolicy<K> named(NamedRetryPolicyRegistry registry, String policyName) {
        return FlowRetryPolicy.<K>builder()
                .namedRegistry(registry)
                .policyName(policyName)
                .build();
    }

    @Override
    public FlowRetryState initialState(K key) {
        return FlowRetryState.initial();
    }

    @Override
    public Before<FlowRetryState> before(PolicyContext context, K key, FlowRetryState state) {
        FlowRetryState current = state != null ? state : FlowRetryState.initial();
        return PersistentPolicy.proceed(current);
    }

    @Override
    public After<FlowRetryState> after(PolicyContext context, K key, FlowRetryState state, Completion completion) {
        FlowRetryState current = state != null ? state : FlowRetryState.initial();
        if (completion != null && completion.kind() == Outcome.Kind.FAILED && completion.failure().isPresent()) {
            Failure failure = completion.failure().get();
            int effectiveMaxAttempts = resolveMaxAttempts();
            if (isRetryable(failure) && current.getAttempt() < effectiveMaxAttempts) {
                Backoff effectiveBackoff = resolveBackoff();
                long delayMillis = effectiveBackoff.calculateMillis(current.getAttempt());
                Instant wakeInstant = delayMillis > 0
                        ? Instant.now().plusMillis(delayMillis)
                        : Instant.now();
                return PersistentPolicy.retryAt(wakeInstant, current.nextAttempt());
            }
        }
        return PersistentPolicy.returning(current);
    }

    /**
     * 判断当前失败是否可被重试。
     *
     * @param failure 步骤失败诊断对象
     * @return 若可重试返回 true，若不可重试返回 false
     */
    public boolean isRetryable(Failure failure) {
        if (failure == null) {
            return false;
        }
        if (retryPredicate != null) {
            return retryPredicate.test(failure);
        }
        return true;
    }

    /**
     * 解析生效的重试策略配置（优先查询 DynamicRetryPolicyRegistry，其次查询 NamedRetryPolicyRegistry）。
     *
     * @return 解析到的基础 RetryPolicy 实例，若未指定或不存在返回 null
     */
    public RetryPolicy resolveRetryPolicy() {
        if (policyName == null || policyName.trim().isEmpty()) {
            return null;
        }
        // 1. 尝试从 DynamicRetryPolicyRegistry 解析
        try {
            Class<?> clazz = Class.forName("com.team4u.framework.retry.dynamic.DynamicRetryPolicyRegistry");
            Method method = clazz.getMethod("getPolicy", String.class);
            RetryPolicy dynamicPolicy = (RetryPolicy) method.invoke(null, policyName);
            if (dynamicPolicy != null) {
                return dynamicPolicy;
            }
        } catch (Throwable ignored) {
            // 类不存在或动态解析失败时忽略
        }
        // 2. 尝试从 NamedRetryPolicyRegistry 解析
        NamedRetryPolicyRegistry registry = namedRegistry != null ? namedRegistry : NamedRetryPolicyRegistry.global();
        return registry.get(policyName)
                .map(NamedRetryPolicyFactory::create)
                .orElse(null);
    }

    /**
     * 解析最终生效的退避算法实例。
     *
     * @return 退避算法实例
     */
    public Backoff resolveBackoff() {
        if (backoff != null) {
            return backoff;
        }
        RetryPolicy dynamicPolicy = resolveRetryPolicy();
        if (dynamicPolicy != null && dynamicPolicy.getBackoff() != null) {
            return dynamicPolicy.getBackoff();
        }
        return Backoffs.fixed(1000);
    }

    /**
     * 解析最终生效的最大尝试次数（含首次执行）。
     *
     * @return 最大尝试总次数
     */
    public int resolveMaxAttempts() {
        if (maxAttempts != null && maxAttempts > 0) {
            return maxAttempts;
        }
        RetryPolicy dynamicPolicy = resolveRetryPolicy();
        if (dynamicPolicy != null) {
            int maxRetries = dynamicPolicy.getMaxRetries();
            return maxRetries >= 0 ? maxRetries + 1 : Integer.MAX_VALUE;
        }
        return 3;
    }

    /**
     * FlowRetryPolicy 自定义 Builder 扩展方法。
     */
    public static class FlowRetryPolicyBuilder<K> {

        public FlowRetryPolicyBuilder<K> retryOn(Predicate<Failure> predicate) {
            this.retryPredicate = predicate;
            return this;
        }

        public FlowRetryPolicyBuilder<K> retryOnCodes(String... codes) {
            Collection<String> codeList = Arrays.asList(codes);
            this.retryPredicate = failure -> failure != null && codeList.contains(failure.code());
            return this;
        }

        public FlowRetryPolicyBuilder<K> abortOnCodes(String... codes) {
            Collection<String> codeList = Arrays.asList(codes);
            this.retryPredicate = failure -> failure != null && !codeList.contains(failure.code());
            return this;
        }
    }
}
