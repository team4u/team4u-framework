package com.team4u.framework.lease;

/**
 * 管理类操作的执行结果。
 */
public enum LeaseAdminResult {
    /**
     * 操作已成功应用。
     */
    APPLIED,
    /**
     * 任务不存在。
     */
    TASK_NOT_FOUND,
    /**
     * 任务已经处于终态。
     */
    TERMINAL,
    /**
     * 任务当前持有有效租约，拒绝直接改写状态。
     */
    ACTIVE_LEASE_PRESENT
}
