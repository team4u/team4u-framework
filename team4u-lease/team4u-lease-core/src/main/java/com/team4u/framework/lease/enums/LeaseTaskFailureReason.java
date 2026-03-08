package com.team4u.framework.lease.enums;

/**
 * 租约任务失败原因
 */
public enum LeaseTaskFailureReason {
    /**
     * 执行器执行过程中抛出异常
     */
    HANDLER_EXCEPTION,
    /**
     * 重试次数已耗尽
     */
    RETRY_EXHAUSTED,
    /**
     * 被策略主动终止执行（如触发熔断或策略校验不通过）
     */
    ABORTED_BY_POLICY,
    /**
     * 找不到对应的处理器
     */
    MISSING_HANDLER,
    /**
     * 人工标记失败
     */
    MANUAL_FAIL
}
