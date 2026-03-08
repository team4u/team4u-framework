package com.team4u.framework.retry;

import com.team4u.framework.retry.backend.RetryBackend;
import com.team4u.framework.retry.backend.RetryCloseReason;
import com.team4u.framework.retry.backend.RetryCloseRequest;
import com.team4u.framework.retry.backend.RetryTaskSnapshot;
import com.team4u.framework.retry.concurrent.RetryExecutorManager;
import com.team4u.framework.retry.policy.NamedRetryPolicy;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 重试执行引擎
 * <p>
 * 提供同步和非阻塞异步重试机制，支持内存模式下的快速重试以及与后端存储集成的持久化重试。
 * 该引擎解耦了重试策略、执行环境和后端持久化逻辑。
 */
public class Retryer {

    private static final int DEFAULT_IN_MEMORY_ATTEMPTS_FOR_PERSISTENCE = 2;

    private final RetryPolicy policy;
    private final RetryBackend retryBackend;
    private final int localAttempts;
    private final Executor cleanupExecutor;

    private Retryer(Builder builder) {
        this.policy = builder.policy;
        this.retryBackend = builder.retryBackend;
        this.localAttempts = resolveLocalAttempts(policy, retryBackend);
        this.cleanupExecutor = builder.cleanupExecutor != null ? builder.cleanupExecutor
                : RetryExecutorManager.global().getCleanupExecutor();
    }

    /**
     * 获取重试引擎构建器
     *
     * @return 构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 使用指定策略快速创建内存模式的重试引擎
     *
     * @param policy 重试策略
     * @return 重试引擎实例
     */
    public static Retryer with(RetryPolicy policy) {
        return builder().policy(policy).build();
    }

    /**
     * 同步执行任务（仅支持内存模式）
     *
     * @param task 待执行的任务回调
     * @param <T>  返回结果类型
     * @return 任务执行结果
     * @throws Exception 任务最终失败抛出的异常或重试过程中的异常
     */
    public <T> T execute(Callable<T> task) throws Exception {
        if (retryBackend != null) {
            throw new IllegalStateException(
                    "Retryer.execute(Callable) supports memory mode only. Use execute(taskType, payloadBuilder, task) "
                            + "or executeAsync(taskType, payloadBuilder, ...) when a persistence adapter is configured.");
        }
        int executedAttempts = 0;
        while (true) {
            try {
                return task.call();
            } catch (Throwable ex) {
                Throwable cause = normalizeSyncFailure(ex);
                RetryDecisionType decision = evaluateDecision(executedAttempts, cause);
                if (decision != RetryDecisionType.RETRY_IN_MEMORY) {
                    if (cause instanceof Exception) {
                        throw (Exception) cause;
                    }
                    throw new RuntimeException(cause);
                }

                long delay = policy.getDelayMillis(executedAttempts + 1);
                if (delay > 0) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
                executedAttempts++;
            }
        }
    }

    /**
     * 异步执行任务（仅支持内存模式）
     *
     * @param asyncTask 提供异步任务的供给者
     * @param scheduler 用于延迟任务调度的执行器
     * @param <T>       返回结果类型
     * @return 包含最终执行结果的 CompletableFuture
     */
    public <T> CompletableFuture<T> executeAsync(
            java.util.function.Supplier<CompletableFuture<T>> asyncTask,
            ScheduledExecutorService scheduler) {
        if (retryBackend != null) {
            throw new IllegalStateException(
                    "Retryer.executeAsync(asyncTask, scheduler) supports memory mode only. "
                            + "Use executeAsync(taskType, payloadBuilder, ...) when a persistence adapter is configured.");
        }

        CompletableFuture<T> promise = new CompletableFuture<T>();
        attemptAsync(null, null, null, asyncTask, scheduler, promise, 0);
        return promise;
    }

    /**
     * 同步执行任务，支持持久化重试模式
     *
     * @param taskType       任务类型标识
     * @param payloadBuilder 重试快照构建器
     * @param task           待执行的任务回调
     * @param <T>            返回结果类型
     * @return 任务执行结果
     * @throws Exception 任务最终失败抛出的异常
     */
    public <T> T execute(String taskType, RetryPayloadBuilder payloadBuilder, Callable<T> task) throws Exception {
        RetryTaskSnapshot snapshot = prepareSnapshot(taskType, payloadBuilder);
        int executedAttempts = 0;
        while (true) {
            try {
                T result = task.call();
                closeSnapshotAsync(snapshot != null ? snapshot.getTaskId() : null, RetryCloseRequest.succeeded());
                return result;
            } catch (Throwable ex) {
                Throwable cause = normalizeSyncFailure(ex);
                RetryDecisionType decision = evaluateDecision(executedAttempts, cause);

                if (decision == RetryDecisionType.FAIL_TERMINAL) {
                    closeSnapshotAsync(snapshot != null ? snapshot.getTaskId() : null, closeFailureRequest(cause));
                    if (cause instanceof Exception) {
                        throw (Exception) cause;
                    }
                    throw new RuntimeException(cause);
                }

                if (decision == RetryDecisionType.HANDOFF_TO_BACKEND) {
                    throw handoffToPersistence(taskType, snapshot, payloadBuilder, executedAttempts, cause);
                }

                long delay = policy.getDelayMillis(executedAttempts + 1);
                if (delay > 0) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
                executedAttempts++;
            }
        }
    }

    private RetryDecisionType evaluateDecision(int executedAttempts, Throwable cause) {
        if (!policy.canRetry(executedAttempts, cause)) {
            return RetryDecisionType.FAIL_TERMINAL;
        }
        if (executedAttempts + 1 < localAttempts) {
            return RetryDecisionType.RETRY_IN_MEMORY;
        }
        if (retryBackend != null && shouldFallbackToPersistence(executedAttempts)) {
            return RetryDecisionType.HANDOFF_TO_BACKEND;
        }
        return RetryDecisionType.FAIL_TERMINAL;
    }

    private int resolveLocalAttempts(RetryPolicy policy, RetryBackend persistenceAdapter) {
        if (persistenceAdapter == null) {
            if (policy.getMaxAttempts() == -1) {
                return Integer.MAX_VALUE;
            }
            return policy.getMaxAttempts();
        }
        int resolved = policy.getLocalAttempts() != null
                ? policy.getLocalAttempts()
                : DEFAULT_IN_MEMORY_ATTEMPTS_FOR_PERSISTENCE;
        if (policy.getMaxAttempts() == -1) {
            return resolved;
        }
        return Math.min(resolved, policy.getMaxAttempts());
    }

    private boolean shouldFallbackToPersistence(int executedAttempts) {
        return policy.getMaxAttempts() == -1 || executedAttempts + 1 < policy.getMaxAttempts();
    }

    /**
     * 异步执行任务，支持持久化重试模式
     *
     * @param taskType       任务类型标识
     * @param payloadBuilder 重试快照构建器
     * @param asyncTask      提供异步任务的供给者
     * @param scheduler      用于延迟任务调度的执行器
     * @param <T>            返回结果类型
     * @return 包含最终执行结果的 CompletableFuture
     */
    public <T> CompletableFuture<T> executeAsync(
            String taskType,
            RetryPayloadBuilder payloadBuilder,
            java.util.function.Supplier<CompletableFuture<T>> asyncTask,
            ScheduledExecutorService scheduler) {
        RetryTaskSnapshot snapshot = prepareSnapshot(taskType, payloadBuilder);

        CompletableFuture<T> promise = new CompletableFuture<T>();
        attemptAsync(taskType, payloadBuilder, snapshot, asyncTask, scheduler, promise, 0);
        return promise;
    }

    private <T> void attemptAsync(
            String taskType, RetryPayloadBuilder payloadBuilder, RetryTaskSnapshot snapshot,
            java.util.function.Supplier<CompletableFuture<T>> asyncTask,
            ScheduledExecutorService scheduler,
            CompletableFuture<T> promise,
            int executedAttempts) {
        try {
            CompletableFuture<T> future = asyncTask.get();
            if (future == null) {
                throw new NullPointerException("asyncTask.get() returned null");
            }
            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    handleAsyncSuccess(snapshot != null ? snapshot.getTaskId() : null, promise, result);
                } else {
                    handleAsyncFailure(taskType, payloadBuilder, snapshot, asyncTask, scheduler, promise,
                            executedAttempts, ex);
                }
            });
        } catch (Throwable ex) {
            handleAsyncFailure(taskType, payloadBuilder, snapshot, asyncTask, scheduler, promise, executedAttempts, ex);
        }
    }

    private <T> void handleAsyncSuccess(String taskId, CompletableFuture<T> promise, T result) {
        closeSnapshotAsync(taskId, RetryCloseRequest.succeeded());
        promise.complete(result);
    }

    private <T> void handleAsyncFailure(
            String taskType, RetryPayloadBuilder payloadBuilder, RetryTaskSnapshot snapshot,
            java.util.function.Supplier<CompletableFuture<T>> asyncTask,
            ScheduledExecutorService scheduler,
            CompletableFuture<T> promise,
            int executedAttempts,
            Throwable ex) {
        Throwable cause = normalizeAsyncFailure(ex);
        if (cause instanceof Error || cause instanceof InterruptedException) {
            promise.completeExceptionally(cause);
            return;
        }

        RetryDecisionType decision = evaluateDecision(executedAttempts, cause);

        if (decision == RetryDecisionType.RETRY_IN_MEMORY) {
            long delay = policy.getDelayMillis(executedAttempts + 1);
            try {
                scheduler.schedule(
                        () -> attemptAsync(taskType, payloadBuilder, snapshot, asyncTask, scheduler, promise,
                                executedAttempts + 1),
                        delay, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                promise.completeExceptionally(e);
            }
            return;
        }

        if (decision == RetryDecisionType.HANDOFF_TO_BACKEND) {
            try {
                promise.completeExceptionally(
                        handoffToPersistence(taskType, snapshot, payloadBuilder, executedAttempts, cause));
            } catch (Exception stateEx) {
                promise.completeExceptionally(stateEx);
            }
            return;
        }

        closeSnapshotAsync(snapshot != null ? snapshot.getTaskId() : null, closeFailureRequest(cause));
        promise.completeExceptionally(cause);
    }

    private RetryTaskSnapshot prepareSnapshot(String taskType, RetryPayloadBuilder payloadBuilder) {
        if (retryBackend == null) {
            return null;
        }
        RetryTaskSnapshot snapshot = payloadBuilder.build(RetryPayloadContext.prepareIntent());
        snapshot.setTaskType(taskType);
        retryBackend.prepare(snapshot);

        if (snapshot.getTaskId() == null || snapshot.getTaskId().isEmpty()) {
            throw new IllegalStateException("Persistent retry intent requires a task id.");
        }

        return snapshot;
    }

    private RetryHandoffException handoffToPersistence(String taskType,
                                                       RetryTaskSnapshot snapshot,
                                                       RetryPayloadBuilder payloadBuilder,
                                                       int executedAttempts,
                                                       Throwable cause) {
        int nextAttempt = executedAttempts + 1;
        RetryTaskSnapshot finalSnapshot = snapshot;

        if (finalSnapshot == null) {
            finalSnapshot = payloadBuilder.build(RetryPayloadContext.handoffToBackend(nextAttempt));
        }

        finalSnapshot.setTaskType(taskType);
        finalSnapshot.setExecutedAttempts(nextAttempt);
        finalSnapshot.setLastError(cause.toString());

        finalSnapshot.setMaxAttempts(policy.getMaxAttempts());
        finalSnapshot.setPolicyKey(policy instanceof NamedRetryPolicy ? ((NamedRetryPolicy) policy).key() : null);

        long delay = policy.getDelayMillis(nextAttempt + 1);

        retryBackend.prepare(finalSnapshot);

        if (finalSnapshot.getTaskId() == null || finalSnapshot.getTaskId().isEmpty()) {
            throw new IllegalStateException("Persistent retry handoff requires a task id.");
        }

        retryBackend.handoff(finalSnapshot.getTaskId(), delay);
        return new RetryHandoffException(
                "In-memory retries exhausted; task has been handed over to persistence storage.",
                cause);
    }

    private RetryCloseRequest closeFailureRequest(Throwable cause) {
        RetryCloseReason reason = policy.getMaxAttempts() != -1 ? RetryCloseReason.RETRY_EXHAUSTED
                : RetryCloseReason.ABORTED_BY_POLICY;
        return RetryCloseRequest.failed(reason, cause == null ? null : cause.toString());
    }

    private void closeSnapshotAsync(String taskId, RetryCloseRequest request) {
        if (taskId == null || retryBackend == null || request == null) {
            return;
        }
        CompletableFuture.runAsync(() -> retryBackend.close(taskId, request), cleanupExecutor);
    }

    private Throwable normalizeSyncFailure(Throwable ex) throws InterruptedException {
        if (ex instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            throw (InterruptedException) ex;
        }
        if (ex instanceof Error) {
            throw (Error) ex;
        }
        Throwable cause = RetryExceptionUtil.unwrap(ex);
        if (cause instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            throw (InterruptedException) cause;
        }
        if (cause instanceof Error) {
            throw (Error) cause;
        }
        return cause;
    }

    private Throwable normalizeAsyncFailure(Throwable ex) {
        if (ex instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return ex;
        }
        if (ex instanceof Error) {
            return ex;
        }
        Throwable cause = RetryExceptionUtil.unwrap(ex);
        if (cause instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        return cause;
    }

    /**
     * 重试决策类型
     */
    public enum RetryDecisionType {
        /**
         * 内存中立即重试或延迟重试
         */
        RETRY_IN_MEMORY,
        /**
         * 移交给后端异步重试
         */
        HANDOFF_TO_BACKEND,
        /**
         * 最终失败，终止重试
         */
        FAIL_TERMINAL
    }

    /**
     * Retryer 构建器
     */
    public static class Builder {
        private RetryPolicy policy;
        private RetryBackend retryBackend;
        private Executor cleanupExecutor;

        /**
         * 设置重试策略
         *
         * @param policy 重试策略
         * @return 构建器自身
         */
        public Builder policy(RetryPolicy policy) {
            this.policy = policy;
            return this;
        }

        /**
         * 设置重试持久化适配器
         *
         * @param retryBackend 持久化适配器
         * @return 构建器自身
         */
        public Builder retryBackend(RetryBackend retryBackend) {
            this.retryBackend = retryBackend;
            return this;
        }

        /**
         * 设置清理任务执行器，用于异步完成或取消后端任务
         *
         * @param cleanupExecutor 清理执行器
         * @return 构建器自身
         */
        public Builder cleanupExecutor(Executor cleanupExecutor) {
            this.cleanupExecutor = cleanupExecutor;
            return this;
        }

        /**
         * 构造 Retryer 实例
         *
         * @return 重试执行引擎
         */
        public Retryer build() {
            if (policy == null) {
                throw new IllegalStateException("RetryPolicy must not be null");
            }
            return new Retryer(this);
        }
    }
}
