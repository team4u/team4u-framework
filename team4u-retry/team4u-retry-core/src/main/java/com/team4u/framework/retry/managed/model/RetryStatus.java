package com.team4u.framework.retry.managed.model;

/**
 * 重试任务的流转状态。
 */
public enum RetryStatus {
    /**
     * 已接受。初始状态，表示重试意图已持久化。
     */
    ACCEPTED,
    /**
     * 等待重试。前台重试失败并已移交后台调度系统，等待下一次运行。
     */
    WAITING_RETRY,
    /**
     * 处理中任务。任务当前正在由某个节点或工作线程执行。
     */
    PROCESSING,
    /**
     * 已成功。任务最终执行成功。
     */
    SUCCEEDED,
    /**
     * 已失败。任务重试耗尽或由于不可恢复的错误而最终失败。
     */
    FAILED,
    /**
     * 已取消。任务已被人工或系统逻辑取消，不再继续执行。
     */
    CANCELLED
}
