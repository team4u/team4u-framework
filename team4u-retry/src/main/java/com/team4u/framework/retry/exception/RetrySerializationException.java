package com.team4u.framework.retry.exception;

/**
 * 重试参数序列化异常
 *
 * 在尝试将方法执行参数持久化为快照时，若发生序列化故障则抛出此异常。
 */
public class RetrySerializationException extends RuntimeException {

    public RetrySerializationException(String message) {
        super(message);
    }

    public RetrySerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
