package com.team4u.framework.retry.backend;

/**
 * 重试任务终止原因
 */
public enum RetryCloseReason {
    /**
     * 已达到策略允许的最大重试次数
     */
    RETRY_EXHAUSTED,
    /**
     * 被特定的重试策略主动终止（如触发熔断、由于特定异常类型不重试等）
     */
    ABORTED_BY_POLICY
}
