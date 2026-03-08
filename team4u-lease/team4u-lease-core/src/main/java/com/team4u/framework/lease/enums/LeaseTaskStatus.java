package com.team4u.framework.lease.enums;

/**
 * 租约任务状态枚举
 * <p>
 * 描述任务在分布式环境下的完整生命周期状态。
 */
public enum LeaseTaskStatus {
    /**
     * 已安排，等待被 Worker 消费（处于可见延迟时间内或在任务池中）
     */
    SCHEDULED,
    /**
     * 已发放租约，目前正在由某个 Worker 处理中
     */
    LEASED,
    /**
     * 执行成功，任务已完成
     */
    SUCCEEDED,
    /**
     * 终态失败，重试耗尽或被强制终止
     */
    DEAD
}
