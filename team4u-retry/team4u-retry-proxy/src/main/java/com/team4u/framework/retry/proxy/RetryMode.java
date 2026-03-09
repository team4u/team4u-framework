package com.team4u.framework.retry.proxy;

/**
 * 重试模式。
 */
public enum RetryMode {
    /**
     * 仅当前进程内执行重试。
     * 所有次测试都在当前线程或通过给定的调度器执行完，如果不成功则抛出最终异常。
     * 不进行持久化托管。
     */
    INLINE,

    /**
     * 进入可持久化托管模型。
     * 任务提交后将被记录至持久化存储，并可由后台 Worker 异步恢复或继续执行。
     */
    MANAGED
}
