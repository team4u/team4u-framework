package com.team4u.framework.lease;

/**
 * 缺失处理器的应对策略枚举。
 */
public enum MissingHandlerStrategy {
    /**
     * 立即失败。将任务标记为 DEAD 状态，不再尝试获取租约。
     */
    FAIL_FAST,
    /**
     * 延迟释放回队列，不计入 failureCount，等待具备处理能力的 Worker。
     */
    RETRY_LATER
}
