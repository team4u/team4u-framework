package com.team4u.framework.lease.enums;

/**
 * 租约任务生命周期状态。
 */
public enum LeaseTaskState {
    /**
     * 可被获取执行。
     */
    READY,
    /**
     * 已被某个 worker 持有并执行中。
     */
    RUNNING,
    /**
     * 已结束，不会再自动推进。
     */
    CLOSED
}
