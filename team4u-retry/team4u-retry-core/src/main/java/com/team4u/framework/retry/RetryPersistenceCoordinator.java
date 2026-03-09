package com.team4u.framework.retry;

import com.team4u.framework.retry.backend.RetryBackend;
import com.team4u.framework.retry.backend.RetryCloseRequest;
import com.team4u.framework.retry.backend.RetryTaskSnapshot;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 重试持久化协调器
 * <p>
 * 负责封装与 {@link RetryBackend} 的所有交互细节，简化重试主流程。
 *
 * @author jay.wu
 */
@RequiredArgsConstructor
public class RetryPersistenceCoordinator {

    private final RetryBackend retryBackend;
    private final String policyKey;
    private final int maxAttempts;
    private final Executor cleanupExecutor;

    public boolean hasRetryBackend() {
        return retryBackend != null;
    }

    /**
     * 预处理重试意图
     */
    public void prepare(RetryExecutionContext<?> context) {
        if (retryBackend == null || !context.isPersistentMode()) {
            return;
        }

        RetryTaskSnapshot snapshot = context.getPayloadBuilder().build(RetryPayloadContext.prepareIntent());
        snapshot.setTaskType(context.getTaskType());
        retryBackend.prepare(snapshot);

        if (snapshot.getTaskId() == null || snapshot.getTaskId().isEmpty()) {
            throw new IllegalStateException("Persistent retry intent requires a task id.");
        }

        context.setSnapshot(snapshot);
    }

    /**
     * 保存当前执行进度
     */
    public void saveProgress(RetryExecutionContext<?> context) {
        if (retryBackend == null || context.getSnapshot() == null) {
            return;
        }

        RetryTaskSnapshot snapshot = context.getSnapshot();
        snapshot.setExecutedAttempts(context.getExecutedAttempts());
        snapshot.setLastError(context.getLastError() != null ? context.getLastError().toString() : null);
        retryBackend.saveProgress(snapshot);
    }

    /**
     * 将任务正式移交给后端持久化
     */
    public void handoff(RetryExecutionContext<?> context, long delayMillis) {
        if (retryBackend == null || !context.isPersistentMode()) {
            return;
        }

        RetryTaskSnapshot finalSnapshot = context.getPayloadBuilder().build(
                RetryPayloadContext.handoffToBackend(context.getExecutedAttempts()));

        // 复用初始意图中的 ID
        if (context.getSnapshot() != null
                && (finalSnapshot.getTaskId() == null || finalSnapshot.getTaskId().isEmpty())) {
            finalSnapshot.setTaskId(context.getSnapshot().getTaskId());
        }

        finalSnapshot.setTaskType(context.getTaskType());
        finalSnapshot.setExecutedAttempts(context.getExecutedAttempts());
        finalSnapshot.setLastError(context.getLastError() != null ? context.getLastError().toString() : null);
        finalSnapshot.setMaxAttempts(maxAttempts);
        finalSnapshot.setPolicyKey(policyKey);

        retryBackend.prepare(finalSnapshot);

        if (finalSnapshot.getTaskId() == null || finalSnapshot.getTaskId().isEmpty()) {
            throw new IllegalStateException("Persistent retry handoff requires a task id.");
        }

        retryBackend.handoff(finalSnapshot.getTaskId(), delayMillis);
    }

    /**
     * 异步关闭成功任务
     */
    public void closeSucceeded(RetryExecutionContext<?> context) {
        closeAsync(context.getTaskId(), RetryCloseRequest.succeeded());
    }

    /**
     * 异步关闭失败任务
     */
    public void closeFailed(RetryExecutionContext<?> context, RetryCloseRequest request) {
        closeAsync(context.getTaskId(), request);
    }

    private void closeAsync(String taskId, RetryCloseRequest request) {
        if (taskId == null || retryBackend == null) {
            return;
        }
        CompletableFuture.runAsync(() -> retryBackend.close(taskId, request), cleanupExecutor);
    }
}
