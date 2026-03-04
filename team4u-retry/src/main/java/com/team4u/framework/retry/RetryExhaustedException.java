package com.team4u.framework.retry;

/**
 * 重试耗尽异常
 * <p>
 * 当内存重试次数达到上限，且任务已被降级到后端存储时抛出。
 */
public class RetryExhaustedException extends RuntimeException {

    public RetryExhaustedException(String message, Throwable cause) {
        super(message, cause);
    }
}
