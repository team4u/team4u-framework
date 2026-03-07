package com.team4u.framework.lease;

/**
 * 不可重试的任务执行异常
 * <p>
 * 如果任务处理器抛出该异常，{@link LeaseWorker} 将忽略配置的重试策略，直接将任务标记为最终失败（DEAD）。
 */
public class NonRetryableLeaseException extends RuntimeException {

    public NonRetryableLeaseException(String message) {
        super(message);
    }

    public NonRetryableLeaseException(String message, Throwable cause) {
        super(message, cause);
    }

    public NonRetryableLeaseException(Throwable cause) {
        super(cause);
    }
}
