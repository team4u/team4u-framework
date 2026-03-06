package com.team4u.framework.lease.backoff;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 失败重试的退避策略接口。
 * <p>
 * 用于计算任务在连续失败多次后，下一次重试前的等待延迟时间。
 * 支持固定延迟、增量延迟、指数退避以及带抖动的指数退避等多种算法。
 */
@FunctionalInterface
public interface Backoff {

    /**
     * 创建固定延迟策略。
     *
     * @param delayMillis 固定的延迟毫秒数
     * @return 退避策略实例
     */
    static Backoff fixed(long delayMillis) {
        return attempt -> delayMillis;
    }

    /**
     * 创建等差增量延迟策略。
     *
     * @param initialDelayMillis 初始延迟时长（毫秒）
     * @param stepMillis         每次尝试增加的步长（毫秒）
     * @return 退避策略实例
     */
    static Backoff increment(long initialDelayMillis, long stepMillis) {
        return attempt -> initialDelayMillis + (attempt - 1) * stepMillis;
    }

    /**
     * 创建指数退避策略。
     *
     * @param initialDelayMillis 初始延迟时长
     * @param multiplier         倍数因子
     * @param maxDelayMillis     最大延迟上限
     * @return 退避策略实例
     */
    static Backoff exponential(long initialDelayMillis, double multiplier, long maxDelayMillis) {
        return attempt -> {
            long delay = (long) (initialDelayMillis * Math.pow(multiplier, attempt - 1));
            return Math.min(delay, maxDelayMillis);
        };
    }

    /**
     * 创建带抖动的指数退避策略。
     * <p>
     * 在指数退避的基础上引入随机因子，有效防止分布式系统中的惊群效应。
     *
     * @param initialDelayMillis 初始延迟时长
     * @param multiplier         倍数因子
     * @param maxDelayMillis     最大延迟上限
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
     * 根据当前尝试次数计算建议延迟时长。
     *
     * @param attempt 当前已尝试次数（通常从 1 开始计数）
     * @return 建议延迟的毫秒数
     */
    long calculateMillis(int attempt);
}
