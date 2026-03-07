package com.team4u.framework.retry;

import com.team4u.framework.retry.backend.RetryBackend;
import com.team4u.framework.retry.concurrent.RetryExecutorManager;
import com.team4u.framework.retry.exception.RetrySerializationException;

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
    private final RetryBackend backendAdapter;
    private final int localAttempts;
    private final Executor cleanupExecutor;

    private Retryer(Builder builder) {
        this.policy = builder.policy;
        this.backendAdapter = builder.backendAdapter;
        this.localAttempts = resolveLocalAttempts(policy, backendAdapter);
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
        if (backendAdapter != null) {
            throw new IllegalStateException(
                    "Retryer.execute(Callable) supports memory mode only. Use execute(taskType, payloadBuilder, task) "
                            + "or executeAsync(taskType, payloadBuilder, ...) when a backend is configured.");
        }
        int attempt = 1;
        while (true) {
            try {
                return task.call();
            } catch (Throwable ex) {
                Throwable cause = normalizeSyncFailure(ex);
                RetryDecisionType decision = evaluateDecision(attempt, cause);
                if (decision == RetryDecisionType.FAIL_TERMINAL || decision == RetryDecisionType.HANDOFF_TO_BACKEND) {
                    if (cause instanceof Exception) {
                        throw (Exception) cause;
                    }
                    throw new RuntimeException(cause);
                }

                long delay = policy.getDelayMillis(attempt);
                if (delay > 0) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
                attempt++;
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
        if (backendAdapter != null) {
            throw new IllegalStateException(
                    "Retryer.executeAsync(asyncTask, scheduler) supports memory mode only. "
                            + "Use executeAsync(taskType, payloadBuilder, ...) when a backend is configured.");
        }

        CompletableFuture<T> promise = new CompletableFuture<T>();
        attemptAsync(null, null, null, asyncTask, scheduler, promise, 1);
        return promise;
    }

    /**
     * 同步执行任务，支持持久化重试模式
     *
     * @param taskType       任务类型标识
     * @param payloadBuilder 重试载荷解析器
     * @param task           待执行的任务回调
     * @param <T>            返回结果类型
     * @return 任务执行结果
     * @throws Exception 任务最终失败抛出的异常
     */
    public <T> T execute(String taskType, RetryPayloadBuilder payloadBuilder, Callable<T> task) throws Exception {
        IntentContext intentContext = prepareIntent(taskType, payloadBuilder);
        int attempt = 1;
        while (true) {
            try {
                T result = task.call();
                completeIntentAsync(intentContext.intentId);
                return result;
            } catch (Throwable ex) {
                Throwable cause = normalizeSyncFailure(ex);
                RetryDecisionType decision = evaluateDecision(attempt, cause);

                if (decision == RetryDecisionType.FAIL_TERMINAL) {
                    completeIntentAsync(intentContext.intentId);
                    if (cause instanceof Exception) {
                        throw (Exception) cause;
                    }
                    throw new RuntimeException(cause);
                }
                if (decision == RetryDecisionType.HANDOFF_TO_BACKEND) {
                    throw enqueueToBackend(intentContext.intentId, cause);
                }

                long delay = policy.getDelayMillis(attempt);
                if (delay > 0) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
                attempt++;
            }
        }
    }

    private RetryDecisionType evaluateDecision(int attempt, Throwable cause) {
        if (!policy.canRetry(attempt, cause)) {
            return RetryDecisionType.FAIL_TERMINAL;
        }
        if (attempt < localAttempts) {
            return RetryDecisionType.RETRY_IN_MEMORY;
        }
        if (backendAdapter != null && shouldFallbackToBackend()) {
            return RetryDecisionType.HANDOFF_TO_BACKEND;
        }
        return RetryDecisionType.FAIL_TERMINAL;
    }

    private int getNextAttemptAfterInMemory() {
        return Math.min(localAttempts + 1,
                policy.getMaxAttempts() == -1 ? localAttempts + 1 : policy.getMaxAttempts());
    }

    private int resolveLocalAttempts(RetryPolicy policy, RetryBackend backendAdapter) {
        if (backendAdapter == null) {
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

    private boolean shouldFallbackToBackend() {
        return policy.getMaxAttempts() == -1 || localAttempts < policy.getMaxAttempts();
    }

    /**
     * 异步执行任务，支持持久化重试模式
     *
     * @param taskType       任务类型标识
     * @param payloadBuilder 重试载荷解析器
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
        IntentContext intentContext = prepareIntent(taskType, payloadBuilder);

        CompletableFuture<T> promise = new CompletableFuture<T>();
        attemptAsync(taskType, payloadBuilder, intentContext.intentId, asyncTask, scheduler, promise, 1);
        return promise;
    }

    private <T> void attemptAsync(
            String taskType, RetryPayloadBuilder payloadBuilder, String intentId,
            java.util.function.Supplier<CompletableFuture<T>> asyncTask,
            ScheduledExecutorService scheduler,
            CompletableFuture<T> promise,
            int attempt) {
        try {
            CompletableFuture<T> future = asyncTask.get();
            if (future == null) {
                throw new NullPointerException("asyncTask.get() returned null");
            }
            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    handleAsyncSuccess(intentId, promise, result);
                } else {
                    handleAsyncFailure(taskType, payloadBuilder, intentId, asyncTask, scheduler, promise,
                            attempt, ex);
                }
            });
        } catch (Throwable ex) {
            handleAsyncFailure(taskType, payloadBuilder, intentId, asyncTask, scheduler, promise, attempt, ex);
        }
    }

    private <T> void handleAsyncSuccess(String intentId, CompletableFuture<T> promise, T result) {
        completeIntentAsync(intentId);
        promise.complete(result);
    }

    private <T> void handleAsyncFailure(
            String taskType, RetryPayloadBuilder payloadBuilder, String intentId,
            java.util.function.Supplier<CompletableFuture<T>> asyncTask,
            ScheduledExecutorService scheduler,
            CompletableFuture<T> promise,
            int attempt,
            Throwable ex) {
        Throwable cause = normalizeAsyncFailure(ex);
        if (cause instanceof Error || cause instanceof InterruptedException) {
            promise.completeExceptionally(cause);
            return;
        }

        RetryDecisionType decision = evaluateDecision(attempt, cause);

        if (decision == RetryDecisionType.RETRY_IN_MEMORY) {
            long delay = policy.getDelayMillis(attempt);
            try {
                scheduler.schedule(
                        () -> attemptAsync(taskType, payloadBuilder, intentId, asyncTask, scheduler, promise,
                                attempt + 1),
                        delay, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                promise.completeExceptionally(e);
            }
            return;
        }

        if (decision == RetryDecisionType.HANDOFF_TO_BACKEND) {
            try {
                promise.completeExceptionally(enqueueToBackend(intentId, cause));
            } catch (IllegalStateException stateEx) {
                promise.completeExceptionally(stateEx);
            }
            return;
        }

        completeIntentAsync(intentId);
        promise.completeExceptionally(cause);
    }

    private IntentContext prepareIntent(String taskType, RetryPayloadBuilder payloadBuilder) {
        if (backendAdapter == null) {
            return new IntentContext(null);
        }
        try {
            String payload = payloadBuilder.build(RetryPayloadContext.prepareIntent());
            String intentId = backendAdapter.prepare(taskType, payload);
            if (intentId == null || intentId.isEmpty()) {
                throw new IllegalStateException(
                        "Persistent retry requires non-null intent id from backendAdapter.prepare()");
            }
            return new IntentContext(intentId);
        } catch (RetrySerializationException e) {
            throw new IllegalStateException(
                    "Persistent retry requires serializable arguments, but serialization failed.", e);
        }
    }

    private RetryHandoffException enqueueToBackend(String intentId, Throwable cause) {
        long nextDelay = policy.getDelayMillis(getNextAttemptAfterInMemory());
        if (intentId == null || intentId.isEmpty()) {
            throw new IllegalStateException("Persistent retry handoff requires a prepared intent id.");
        }
        backendAdapter.handoff(intentId, nextDelay);
        return new RetryHandoffException("In-memory retries exhausted; task has been handed over to backend queue.",
                cause);
    }

    private void completeIntentAsync(String intentId) {
        if (intentId == null || backendAdapter == null) {
            return;
        }
        CompletableFuture.runAsync(() -> backendAdapter.complete(intentId), cleanupExecutor);
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

    private static final class IntentContext {
        private final String intentId;

        private IntentContext(String intentId) {
            this.intentId = intentId;
        }
    }

    /**
     * Retryer 构建器
     */
    public static class Builder {
        private RetryPolicy policy;
        private RetryBackend backendAdapter;
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
         * 设置重试后端
         *
         * @param backendAdapter 后端适配器
         * @return 构建器自身
         */
        public Builder backend(RetryBackend backendAdapter) {
            this.backendAdapter = backendAdapter;
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
