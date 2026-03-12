package com.team4u.framework.retry.common.backoff;

/**
 * 失败退避策略接口
 * <p>
 * 用于在任务失败重试时计算下一次尝试前的等待时间。
 * 通过不同的实现逻辑（如固定延迟、线性递增、指数退避等），可以有效压制故障扩散并保护下游资源。
 */
public interface Backoff {

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