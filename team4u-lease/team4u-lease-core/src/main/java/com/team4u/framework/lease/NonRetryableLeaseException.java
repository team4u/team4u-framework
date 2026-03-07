package com.team4u.framework.lease;

/**
 * 不可重试失败。
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
