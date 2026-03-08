package com.team4u.framework.lease.enums;

/**
 * 运行时租约写回结果。
 */
public enum LeaseRuntimeResult {
    /**
     * 操作已成功应用。
     */
    APPLIED,
    /**
     * 当前 worker 不再持有该任务的有效租约。
     */
    LEASE_LOST,
    /**
     * 任务不存在。
     */
    TASK_NOT_FOUND,
    /**
     * 任务已经进入终态。
     */
    TERMINAL
}
