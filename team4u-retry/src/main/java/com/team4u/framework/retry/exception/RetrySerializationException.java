package com.team4u.framework.retry.exception;

/**
 * 重试参数序列化异常
 * <p>
 * 当无法将重试方法的参数序列化为快照时抛出此异常，
 * 用于区分业务异常与框架级序列化异常。
 *
 * @author antigravity
 */
public class RetrySerializationException extends RuntimeException {

    public RetrySerializationException(String message) {
        super(message);
    }

    public RetrySerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
