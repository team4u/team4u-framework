package com.team4u.framework.retry;

/**
 * 重试尝试耗尽异常
 * <p>
 * 当内存重试达到设定上限，且任务已成功转移至后端持久化队列时抛出。
 */
public class RetryExhaustedException extends RuntimeException {

    public RetryExhaustedException(String message, Throwable cause) {
        super(message, cause);
    }
}
