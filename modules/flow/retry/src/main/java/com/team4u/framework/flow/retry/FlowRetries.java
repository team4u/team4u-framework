package com.team4u.framework.flow.retry;

import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.model.Failure;
import com.team4u.framework.retry.api.NamedRetryPolicyRegistry;
import com.team4u.framework.retry.common.backoff.Backoff;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 流程重试治理策略便捷工厂类与 DSL 工具。
 * <p>
 * 提供快捷创建各种退避算法策略（固定、递增、指数、随机抖动等）、命名规则引用及挂载到 Flow 流程的辅助方法。
 * </p>
 *
 * @author jay.wu
 */
public final class FlowRetries {

    private FlowRetries() { }

    /**
     * 创建基于指定最大尝试次数与退避策略的重试策略。
     *
     * @param maxAttempts 最大尝试次数（包含初试，>= 1）
     * @param backoff     退避策略
     * @param <K>         策略键类型
     * @return FlowRetryPolicy 实例
     */
    public static <K> FlowRetryPolicy<K> of(int maxAttempts, Backoff backoff) {
        return FlowRetryPolicy.of(maxAttempts, backoff);
    }

    /**
     * 创建基于指定最大尝试次数、退避策略及失败判定谓词的重试策略。
     *
     * @param maxAttempts    最大尝试次数（包含初试，>= 1）
     * @param backoff        退避策略
     * @param retryPredicate 失败重试判定谓词
     * @param <K>            策略键类型
     * @return FlowRetryPolicy 实例
     */
    public static <K> FlowRetryPolicy<K> of(int maxAttempts, Backoff backoff, Predicate<Failure> retryPredicate) {
        return FlowRetryPolicy.of(maxAttempts, backoff, retryPredicate);
    }

    /**
     * 创建指数退避重试策略。
     *
     * @param maxAttempts        最大尝试次数（包含初试，>= 1）
     * @param initialDelayMillis 初始延迟毫秒数
     * @param multiplier         倍数
     * @param maxDelayMillis     最大延迟毫秒数
     * @param <K>                策略键类型
     * @return FlowRetryPolicy 实例
     */
    public static <K> FlowRetryPolicy<K> exponential(int maxAttempts, long initialDelayMillis, double multiplier, long maxDelayMillis) {
        return FlowRetryPolicy.exponential(maxAttempts, initialDelayMillis, multiplier, maxDelayMillis);
    }

    /**
     * 创建带随机抖动的指数退避重试策略。
     *
     * @param maxAttempts        最大尝试次数（包含初试，>= 1）
     * @param initialDelayMillis 初始延迟毫秒数
     * @param multiplier         倍数
     * @param maxDelayMillis     最大延迟毫秒数
     * @param <K>                策略键类型
     * @return FlowRetryPolicy 实例
     */
    public static <K> FlowRetryPolicy<K> jitter(int maxAttempts, long initialDelayMillis, double multiplier, long maxDelayMillis) {
        return FlowRetryPolicy.jitter(maxAttempts, initialDelayMillis, multiplier, maxDelayMillis);
    }

    /**
     * 创建固定延迟退避重试策略。
     *
     * @param maxAttempts 最大尝试次数（包含初试，>= 1）
     * @param delayMillis 延迟毫秒数
     * @param <K>         策略键类型
     * @return FlowRetryPolicy 实例
     */
    public static <K> FlowRetryPolicy<K> fixed(int maxAttempts, long delayMillis) {
        return FlowRetryPolicy.fixed(maxAttempts, delayMillis);
    }

    /**
     * 创建递增延迟退避重试策略。
     *
     * @param maxAttempts          最大尝试次数（包含初试，>= 1）
     * @param initialDelayMillis   初始延迟毫秒数
     * @param incrementDelayMillis 递增延迟毫秒数
     * @param <K>                  策略键类型
     * @return FlowRetryPolicy 实例
     */
    public static <K> FlowRetryPolicy<K> increment(int maxAttempts, long initialDelayMillis, long incrementDelayMillis) {
        return FlowRetryPolicy.increment(maxAttempts, initialDelayMillis, incrementDelayMillis);
    }

    /**
     * 创建基于动态/命名规则的重试策略。
     *
     * @param policyName 策略名称
     * @param <K>        策略键类型
     * @return FlowRetryPolicy 实例
     */
    public static <K> FlowRetryPolicy<K> named(String policyName) {
        return FlowRetryPolicy.named(policyName);
    }

    /**
     * 创建基于指定命名注册表与策略名称的重试策略。
     *
     * @param registry   命名策略注册表
     * @param policyName 策略名称
     * @param <K>        策略键类型
     * @return FlowRetryPolicy 实例
     */
    public static <K> FlowRetryPolicy<K> named(NamedRetryPolicyRegistry registry, String policyName) {
        return FlowRetryPolicy.named(registry, policyName);
    }

    /**
     * 创建流式构建器。
     *
     * @param <K> 策略键类型
     * @return FlowRetryPolicyBuilder 实例
     */
    public static <K> FlowRetryPolicy.FlowRetryPolicyBuilder<K> builder() {
        return FlowRetryPolicy.builder();
    }

    /**
     * 将目标 Flow 挂载重试策略（支持键投影）。
     *
     * @param flow          目标流程
     * @param policy        重试策略
     * @param keyProjection 策略键投影提取函数
     * @param <I>           流程输入类型
     * @param <O>           流程输出类型
     * @param <K>           策略键类型
     * @return 附加重试治理后的新 Flow 实例
     */
    public static <I, O, K> Flow<I, O> wrap(Flow<I, O> flow, FlowRetryPolicy<K> policy, Function<? super I, ? extends K> keyProjection) {
        Objects.requireNonNull(flow, "flow must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        return policy.wrap(flow, keyProjection);
    }

    /**
     * 将目标 Flow 挂载重试策略（恒等输入键投影）。
     *
     * @param flow   目标流程
     * @param policy 重试策略
     * @param <I>    流程输入类型
     * @param <O>    流程输出类型
     * @return 附加重试治理后的新 Flow 实例
     */
    public static <I, O> Flow<I, O> wrap(Flow<I, O> flow, FlowRetryPolicy<I> policy) {
        return wrap(flow, policy, Function.identity());
    }

    /**
     * 将目标 Flow 挂载重试策略（支持键投影，语义同 wrap）。
     *
     * @param flow          目标流程
     * @param policy        重试策略
     * @param keyProjection 策略键投影提取函数
     * @param <I>           流程输入类型
     * @param <O>           流程输出类型
     * @param <K>           策略键类型
     * @return 附加重试治理后的新 Flow 实例
     */
    public static <I, O, K> Flow<I, O> policy(Flow<I, O> flow, FlowRetryPolicy<K> policy, Function<? super I, ? extends K> keyProjection) {
        return wrap(flow, policy, keyProjection);
    }

    /**
     * 将目标 Flow 挂载重试策略（恒等输入键投影，语义同 wrap）。
     *
     * @param flow   目标流程
     * @param policy 重试策略
     * @param <I>    流程输入类型
     * @param <O>    流程输出类型
     * @return 附加重试治理后的新 Flow 实例
     */
    public static <I, O> Flow<I, O> policy(Flow<I, O> flow, FlowRetryPolicy<I> policy) {
        return wrap(flow, policy, Function.identity());
    }
}
