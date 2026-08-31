package com.team4u.framework.flow.retry;

import com.team4u.framework.base.cache.Cache;
import com.team4u.framework.base.cache.CacheUtil;
import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.api.Gate;
import com.team4u.framework.flow.api.Policy;
import com.team4u.framework.flow.api.PolicyContext;
import com.team4u.framework.flow.api.Retry;
import com.team4u.framework.flow.model.Completion;
import com.team4u.framework.flow.model.Failure;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Reason;
import com.team4u.framework.retry.api.NamedRetryPolicyFactory;
import com.team4u.framework.retry.api.NamedRetryPolicyRegistry;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.common.backoff.Backoff;
import com.team4u.framework.retry.common.backoff.Backoffs;
import lombok.Builder;
import lombok.Getter;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 基于 team4u-retry 的流程重试与退避治理策略适配器
 * <p>
 * 通过将 team4u-retry 模块的 Backoff 退避体系（{@link Backoff}、{@link Backoffs}）、
 * 条件重试判定（{@link Predicate<Failure>}）、最大尝试次数控制以及动态规则注册中心
 * （{@link NamedRetryPolicyRegistry} / {@code DynamicRetryPolicyRegistry}）与 Flow 的 {@link Policy}
 * 网关机制深度融合，实现精细化、多算法与自适应的流程步骤重试控制。
 * </p>
 *
 * @param <K> 策略路由键（或步骤入参）类型
 * @author jay.wu
 */
@Getter
public class FlowRetryPolicy<K> implements Policy<K> {

    /**
     * 默认不可重试异常中止诊断码
     */
    public static final String DEFAULT_ABORT_CODE = "NON_RETRYABLE";

    /**
     * 默认重试次数耗尽诊断码
     */
    public static final String DEFAULT_EXHAUSTED_CODE = "RETRY_EXHAUSTED";

    /**
     * 默认重试等待被中断诊断码
     */
    public static final String DEFAULT_INTERRUPTED_CODE = "RETRY_INTERRUPTED";

    private final Integer maxAttempts;
    private final Backoff backoff;
    private final Predicate<Failure> retryPredicate;
    private final String policyName;
    private final NamedRetryPolicyRegistry namedRegistry;
    private final boolean sleepOnRetry;
    private final String nonRetryableReasonCode;
    private final BiFunction<Failure, K, Reason> reasonFactory;
    private final BiFunction<Integer, K, Failure> failureFactory;
    private final Function<K, ?> contextExtractor;
    private final Cache<String, NonRetryableRecord> nonRetryableCache;
    private final Cache<String, AtomicInteger> attemptCache;

    @Builder(toBuilder = true)
    public FlowRetryPolicy(
            Integer maxAttempts,
            Backoff backoff,
            Predicate<Failure> retryPredicate,
            String policyName,
            NamedRetryPolicyRegistry namedRegistry,
            Boolean sleepOnRetry,
            String nonRetryableReasonCode,
            BiFunction<Failure, K, Reason> reasonFactory,
            BiFunction<Integer, K, Failure> failureFactory,
            Function<K, ?> contextExtractor) {
        if (maxAttempts != null && maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be greater than 0");
        }
        this.maxAttempts = maxAttempts;
        this.backoff = backoff;
        this.retryPredicate = retryPredicate;
        this.policyName = policyName;
        this.namedRegistry = namedRegistry;
        this.sleepOnRetry = sleepOnRetry != null ? sleepOnRetry : true;
        this.nonRetryableReasonCode = nonRetryableReasonCode != null ? nonRetryableReasonCode : DEFAULT_ABORT_CODE;
        this.reasonFactory = reasonFactory;
        this.failureFactory = failureFactory;
        this.contextExtractor = contextExtractor;
        this.nonRetryableCache = CacheUtil.newLRUCache(1000);
        this.attemptCache = CacheUtil.newLRUCache(1000);
    }

    /**
     * 创建基于指定最大尝试次数与退避策略的重试策略
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
     * 创建基于指定最大尝试次数、退避策略及失败判定谓词的重试策略
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
     * 创建指数退避重试策略
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
     * 创建带随机抖动的指数退避重试策略
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
     * 创建固定延迟退避重试策略
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
     * 创建基于动态/命名规则的重试策略
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
     * 创建基于指定命名注册表与策略名称的重试策略
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
    public Gate before(PolicyContext context, K key) {
        String execKey = executionKey(context);
        NonRetryableRecord record = nonRetryableCache.get(execKey);
        if (record != null) {
            nonRetryableCache.remove(execKey);
            attemptCache.remove(execKey);
            String code = nonRetryableReasonCode != null ? nonRetryableReasonCode : DEFAULT_ABORT_CODE;
            Reason reason = reasonFactory != null
                    ? reasonFactory.apply(record.getFailure(), key)
                    : Reason.of(code, "Retry aborted: non-retryable failure ["
                            + record.getFailure().code() + "] " + record.getFailure().message());
            return Gate.reject(reason);
        }

        int attempt = nextAttempt(execKey, context);
        int effectiveMaxAttempts = resolveMaxAttempts();
        if (effectiveMaxAttempts > 0 && attempt > effectiveMaxAttempts) {
            nonRetryableCache.remove(execKey);
            attemptCache.remove(execKey);
            Failure failure = failureFactory != null
                    ? failureFactory.apply(attempt, key)
                    : Failure.of(DEFAULT_EXHAUSTED_CODE, "Retry attempts exhausted: " + attempt + "/" + effectiveMaxAttempts);
            return Gate.fail(failure);
        }

        if (attempt > 1) {
            Backoff effectiveBackoff = resolveBackoff();
            long delayMillis = effectiveBackoff.calculateMillis(attempt - 1);
            if (sleepOnRetry && delayMillis > 0) {
                if (context != null && context.cancellation() != null) {
                    context.cancellation().throwIfCancelled();
                }
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (context != null && context.cancellation() != null && context.cancellation().isCancelled()) {
                        context.cancellation().throwIfCancelled();
                    }
                    return Gate.fail(Failure.of(DEFAULT_INTERRUPTED_CODE, "Retry backoff interrupted: " + e.getMessage()));
                }
            }
        }

        return Gate.proceed();
    }

    @Override
    public void after(PolicyContext context, K key, Completion completion) {
        String execKey = executionKey(context);
        if (completion != null && completion.kind() == Outcome.Kind.FAILED) {
            Failure failure = completion.failure().orElse(null);
            if (failure != null && !isRetryable(failure)) {
                nonRetryableCache.put(execKey, new NonRetryableRecord(failure));
            }
        } else {
            nonRetryableCache.remove(execKey);
            attemptCache.remove(execKey);
        }
    }

    private int nextAttempt(String execKey, PolicyContext context) {
        int ctxAttempt = context != null ? context.attempt() : 1;
        AtomicInteger counter = attemptCache.get(execKey);
        if (counter == null) {
            counter = new AtomicInteger(Math.max(1, ctxAttempt));
            attemptCache.put(execKey, counter);
            return counter.get();
        }
        int next = counter.incrementAndGet();
        return Math.max(next, ctxAttempt);
    }

    /**
     * 判断当前失败是否可被重试
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
     * 解析生效的重试策略配置（优先查询 DynamicRetryPolicyRegistry，其次查询 NamedRetryPolicyRegistry）
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
     * 解析最终生效的退避算法实例
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
     * 解析最终生效的最大尝试次数（含首次执行）
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
     * 将当前重试策略转化为 Flow 专用的 Retry 控制配置（无额外外部退避延迟，由 Policy 统一精确控制算法退避）。
     *
     * @return 不可变 Retry 配置实例
     */
    public Retry toRetry() {
        return Retry.maxAttempts(resolveMaxAttempts());
    }

    /**
     * 将当前重试策略转化为指定外部退避时长的 Retry 配置。
     *
     * @param externalBackoff 外部退避时长
     * @return 不可变 Retry 配置实例
     */
    public Retry toRetry(Duration externalBackoff) {
        return new Retry(resolveMaxAttempts(), externalBackoff);
    }

    /**
     * 将当前重试策略与 Flow 编排治理无缝结合（构建 [Retry 控制 -> [Policy 策略 -> 步骤]] 的标准洋葱模型）。
     *
     * @param flow          目标流程
     * @param keyProjection 策略键投影提取函数
     * @param <I>           流程输入类型
     * @param <O>           流程输出类型
     * @return 附加重试与退避治理后的新 Flow 实例
     */
    public <I, O> Flow<I, O> wrap(Flow<I, O> flow, Function<? super I, ? extends K> keyProjection) {
        return flow.policy(this, keyProjection).retry(toRetry());
    }

    private String executionKey(PolicyContext context) {
        if (context == null) {
            return String.valueOf(Thread.currentThread().getId());
        }
        if (context.metadata() != null) {
            return context.metadata().executionId() + ":" + context.metadata().nodePath();
        }
        return String.valueOf(Thread.currentThread().getId());
    }

    @Getter
    private static final class NonRetryableRecord {
        private final Failure failure;

        private NonRetryableRecord(Failure failure) {
            this.failure = failure;
        }
    }

    /**
     * FlowRetryPolicy 自定义 Builder 扩展方法
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
