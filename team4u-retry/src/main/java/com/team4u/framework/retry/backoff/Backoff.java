package com.team4u.framework.retry.backoff;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 重试退避策略
 *
 * 用于计算任务重试前的等待延迟。
 */
@FunctionalInterface
public interface Backoff {

    /**
     * 固定间隔退避策略
     *
     * @param delayMillis 固定的延迟时长（毫秒）
     * @return 退避策略实例
     */
    static Backoff fixed(long delayMillis) {
        return attempt -> delayMillis;
    }

    /**
     * 线性增量退避策略
     *
     * 延迟时长随重试次数线性增长。
     * 公式：initialDelay + (attempt - 1) * step
     *
     * @param initialDelayMillis 初始延迟时长（毫秒）
     * @param stepMillis         每次递增的步长（毫秒）
     * @return 退避策略实例
     */
    static Backoff increment(long initialDelayMillis, long stepMillis) {
        return attempt -> initialDelayMillis + (attempt - 1) * stepMillis;
    }

    /**
     * 指数退避策略
     *
     * 延迟时长随重试次数呈指数级增长，直至达到最大限制。
     * 公式：min(initialDelay * multiplier^(attempt - 1), maxDelay)
     *
     * @param initialDelayMillis 初始延迟时长（毫秒）
     * @param multiplier         增长乘数
     * @param maxDelayMillis     最大允许延迟时长（毫秒）
     * @return 退避策略实例
     */
    static Backoff exponential(long initialDelayMillis, double multiplier, long maxDelayMillis) {
        return attempt -> {
            long delay = (long) (initialDelayMillis * Math.pow(multiplier, attempt - 1));
            return Math.min(delay, maxDelayMillis);
        };
    }

    /**
     * 带随机抖动的指数退避策略
     *
     * 在指数退避基础上引入随机因子，有效分散系统瞬时产生的高并发重试请求，
     * 缓解集群雪崩效应。
     *
     * @param initialDelayMillis 初始延迟时长（毫秒）
     * @param multiplier         增长乘数
     * @param maxDelayMillis     最大允许延迟时长（毫秒）
     * @return 退避策略实例
     */
    static Backoff exponentialJitter(long initialDelayMillis, double multiplier, long maxDelayMillis) {
        return attempt -> {
            long maxCalculatedDelay = exponential(initialDelayMillis, multiplier, maxDelayMillis)
                    .calculateMillis(attempt);
            if (maxCalculatedDelay <= initialDelayMillis) {
                return maxCalculatedDelay;
            }
            return ThreadLocalRandom.current().nextLong(initialDelayMillis, maxCalculatedDelay + 1);
        };
    }

    /**
     * 计算当前重试轮次的等待时间
     *
     * @param attempt 当前已执行尝试次数（从 1 开始）
     * @return 延迟等待毫秒数
     */
    long calculateMillis(int attempt);
}
