package com.team4u.framework.retry.backend;

/**
 * 重试任务结束结果
 */
public enum RetryCloseOutcome {
    /**
     * 任务成功完成
     */
    SUCCEEDED,
    /**
     * 任务执行失败，且根据策略不再继续重试
     */
    FAILED,
    /**
     * 任务被外部主动取消
     */
    CANCELLED
}
