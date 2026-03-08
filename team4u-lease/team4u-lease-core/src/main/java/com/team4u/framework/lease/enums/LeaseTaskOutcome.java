package com.team4u.framework.lease.enums;

/**
 * 租约任务结束结果
 */
public enum LeaseTaskOutcome {
    /**
     * 任务成功执行
     */
    SUCCEEDED,
    /**
     * 任务执行失败，且不再继续
     */
    FAILED,
    /**
     * 任务被外部主动取消或中止
     */
    CANCELLED
}
