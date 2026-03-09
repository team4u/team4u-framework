package com.team4u.framework.retry.exception;

/**
 * 重试任务移交异常
 * <p>
 * 当本地进程内的重试尝试次数已达到配额上限，且任务已成功移交至后端持久化队列（如数据库或消息队列）时抛出。
 * <p>
 * 该异常不代表任务最终失败，而是作为一个控制信号，告知调用者本地同步或异步重试流程已结束，
 * 任务已转入后台持久化层进行后续处理。
 */
public class RetryHandoffException extends RuntimeException {

    /**
     * 构造重试任务移交异常
     *
     * @param message 异常描述信息
     * @param cause   引发移交状态的原始异常
     */
    public RetryHandoffException(String message, Throwable cause) {
        super(message, cause);
    }
}