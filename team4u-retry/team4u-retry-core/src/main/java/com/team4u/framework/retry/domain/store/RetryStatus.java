package com.team4u.framework.retry.domain.store;

/**
 * 重试任务的流转状态。
 */
public enum RetryStatus {
    PREPARED,
    RUNNING,
    SCHEDULED,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
