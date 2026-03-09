package com.team4u.framework.retry;

import com.team4u.framework.retry.backend.RetryPayloadBuilder;
import com.team4u.framework.retry.backend.RetryTaskSnapshot;
import lombok.Getter;
import lombok.Setter;

import java.util.concurrent.CompletableFuture;

/**
 * 重试执行上下文
 * <p>
 * 封装单次重试任务执行过程中的所有状态信息。
 *
 * @author jay.wu
 */
@Getter
@Setter
public class RetryExecutionContext<T> {

    /** 任务类型标识 */
    private final String taskType;
    /** 重试快照构建器 */
    private final RetryPayloadBuilder payloadBuilder;
    /** 持久化任务快照（仅在开启持久化模式下存在） */
    private RetryTaskSnapshot snapshot;
    /** 已执行的尝试次数 */
    private int executedAttempts;
    /** 最后一次发生的异常 */
    private Throwable lastError;
    /** 用于异步结果通知的 Promise */
    private CompletableFuture<T> promise;

    public RetryExecutionContext(String taskType, RetryPayloadBuilder payloadBuilder) {
        this.taskType = taskType;
        this.payloadBuilder = payloadBuilder;
    }

    /**
     * 更新执行进度
     *
     * @param cause 异常原因
     */
    public void updateProgress(Throwable cause) {
        this.executedAttempts++;
        this.lastError = cause;
    }

    /**
     * 获取任务 ID
     */
    public String getTaskId() {
        return snapshot != null ? snapshot.getTaskId() : null;
    }

    public boolean isPersistentMode() {
        return payloadBuilder != null;
    }
}
