package com.team4u.framework.retry.backoff;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 退避策略
 * <p>
 * 用于计算下一次重试前的等待时间。
 */
@FunctionalInterface
public interface Backoff {

    /**
     * 固定间隔重试策略
     *
     * @param delayMillis 固定的延迟时间，单位：毫秒
     * @return 退避策略实例
     */
    static Backoff fixed(long delayMillis) {
        return attempt -> delayMillis;
    }

    /**
     * 增量间隔重试策略
     * <p>
     * 随着重试次数增加，延迟时间线性增长。
     * 例如，初始延迟为1000ms，步长为1000ms：
     * 第1次重试：1000ms
     * 第2次重试：2000ms
     * 第3次重试：3000ms
     *
     * @param initialDelayMillis 初始延迟时间，单位：毫秒
     * @param stepMillis         每次递增的步长，单位：毫秒
     * @return 退避策略实例
     */
    static Backoff increment(long initialDelayMillis, long stepMillis) {
        return attempt -> initialDelayMillis + (attempt - 1) * stepMillis;
    }

    /**
     * 指数间隔退避策略
     * <p>
     * 随着重试次数增加，延迟时间呈指数增长，直至达到最大延迟限制。
     * 例如，初始延迟为1000ms，乘数为2.0，最大为10000ms：
     * 第1次重试：1000ms
     * 第2次重试：2000ms
     * 第3次重试：4000ms
     *
     * @param initialDelayMillis 初始延迟时间，单位：毫秒
     * @param multiplier         每次递增的乘数
     * @param maxDelayMillis     最大允许的延迟时间，单位：毫秒
     * @return 退避策略实例
     */
    static Backoff exponential(long initialDelayMillis, double multiplier, long maxDelayMillis) {
        return attempt -> {
            long delay = (long) (initialDelayMillis * Math.pow(multiplier, attempt - 1));
            return Math.min(delay, maxDelayMillis);
        };
    }

    /**
     * 全抖动指数退避策略
     * <p>
     * 在指数级退避的基础上加入了随机因子，有助于打散系统中瞬间产生的高并发重试请求，
     * 以防止出现雪崩或惊群效应。
     *
     * @param initialDelayMillis 初始延迟时间，单位：毫秒
     * @param multiplier         每次递增的乘数
     * @param maxDelayMillis     最大允许的延迟时间，单位：毫秒
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
     * 计算等待时间
     *
     * @param attempt 当前已尝试次数，从1开始
     * @return 延迟的毫秒数
     */
    long calculateMillis(int attempt);
}
