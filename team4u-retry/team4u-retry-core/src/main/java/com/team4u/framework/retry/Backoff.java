package com.team4u.framework.retry;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 失败退避策略接口
 * <p>
 * 用于在任务失败重试时计算下一次尝试前的等待时间。
 * 通过不同的实现逻辑（如固定延迟、线性递增、指数退避等），可以有效压制故障扩散并保护下游资源。
 */
@FunctionalInterface
public interface Backoff {

    /**
     * 创建固定延迟退避策略
     *
     * @param delayMillis 固定延迟毫秒数
     * @return 退避策略实例
     */
    static Backoff fixed(long delayMillis) {
        if (delayMillis < 0L) {
            throw new IllegalArgumentException("delayMillis must be greater than or equal to 0");
        }
        return attempt -> {
            validateAttempt(attempt);
            return delayMillis;
        };
    }

    /**
     * 创建等差递增延迟退避策略
     * <p>
     * 计算公式：initialDelayMillis + (attempt - 1) * stepMillis
     *
     * @param initialDelayMillis 初始延迟毫秒数
     * @param stepMillis         每次尝试增加的步长毫秒数
     * @return 退避策略实例
     */
    static Backoff increment(long initialDelayMillis, long stepMillis) {
        if (initialDelayMillis < 0L) {
            throw new IllegalArgumentException("initialDelayMillis must be greater than or equal to 0");
        }
        if (stepMillis < 0L) {
            throw new IllegalArgumentException("stepMillis must be greater than or equal to 0");
        }
        return attempt -> {
            validateAttempt(attempt);
            return initialDelayMillis + (attempt - 1L) * stepMillis;
        };
    }

    /**
     * 创建指数级退避策略
     * <p>
     * 计算公式：initialDelayMillis * (multiplier ^ (attempt - 1))，且不超过 maxDelayMillis。
     *
     * @param initialDelayMillis 初始延迟毫秒数
     * @param multiplier         倍数乘子项
     * @param maxDelayMillis     最大延迟毫秒数上限
     * @return 退避策略实例
     */
    static Backoff exponential(long initialDelayMillis, double multiplier, long maxDelayMillis) {
        if (initialDelayMillis < 0L) {
            throw new IllegalArgumentException("initialDelayMillis must be greater than or equal to 0");
        }
        if (multiplier <= 0D) {
            throw new IllegalArgumentException("multiplier must be greater than 0");
        }
        if (maxDelayMillis < initialDelayMillis) {
            throw new IllegalArgumentException("maxDelayMillis must be greater than or equal to initialDelayMillis");
        }
        return attempt -> {
            validateAttempt(attempt);
            long delay = (long) (initialDelayMillis * Math.pow(multiplier, attempt - 1));
            return Math.min(delay, maxDelayMillis);
        };
    }

    /**
     * 创建带随机抖动（Jitter）的指数级退避策略
     * <p>
     * 在指数延迟的基础上增加随机性，避免同一时刻大量任务同时发起重试（惊群效应）。
     *
     * @param initialDelayMillis 初始延迟毫秒数
     * @param multiplier         倍数乘子项
     * @param maxDelayMillis     最大延迟毫秒数上限
     * @return 退避策略实例
     */
    static Backoff exponentialJitter(long initialDelayMillis, double multiplier, long maxDelayMillis) {
        return attempt -> {
            validateAttempt(attempt);
            long maxCalculatedDelay = exponential(initialDelayMillis, multiplier, maxDelayMillis)
                    .calculateMillis(attempt);
            if (maxCalculatedDelay <= initialDelayMillis) {
                return maxCalculatedDelay;
            }
            return ThreadLocalRandom.current().nextLong(initialDelayMillis, maxCalculatedDelay + 1L);
        };
    }

    /**
     * 校验尝试次数是否合法
     *
     * @param attempt 尝试次数（必须从 1 开始）
     */
    static void validateAttempt(int attempt) {
        if (attempt <= 0) {
            throw new IllegalArgumentException("attempt must be greater than 0");
        }
    }

    /**
     * 根据当前尝试次数计算延迟毫秒数
     *
     * @param attempt 当前尝试次数（从 1 开始计数，1 表示首次尝试失败后的重试等待）
     * @return 需要等待的延迟毫秒数
     */
    long calculateMillis(int attempt);
}