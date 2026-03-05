package com.team4u.framework.retry;

import com.team4u.framework.retry.exception.RetrySerializationException;

import com.team4u.framework.retry.concurrent.RetryExecutorManager;

import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * 统一重试执行引擎
 * <p>
 * 这是处理重试逻辑的核心组件，提供了同步和真非阻塞的异步重试机制。
 */
public class Retryer {

    private static final int DEFAULT_IN_MEMORY_ATTEMPTS_FOR_PERSISTENCE = 2;

    private final RetryPolicy policy;
    private final RetryBackend backend;
    private final RetryDurability durability;
    private final int inMemoryAttempts;
    private final Executor cleanupExecutor;

    private Retryer(Builder builder) {
        this.policy = builder.policy;
        this.backend = builder.backend;
        this.durability = builder.durability != null ? builder.durability : RetryDurability.MEMORY_ONLY;
        this.inMemoryAttempts = resolveInMemoryAttempts(policy, this.durability);
        this.cleanupExecutor = builder.cleanupExecutor != null ? builder.cleanupExecutor
                : RetryExecutorManager.global().getCleanupExecutor();
    }

    /**
     * 创建重试引擎建造者
     *
     * @return 建造者对象
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 根据指定的策略初始化一个重试引擎
     *
     * @param policy 包含各种重试参数的不可变策略
     * @return 准备就绪的重试引擎
     */
    public static Retryer with(RetryPolicy policy) {
        return builder().policy(policy).build();
    }

    /**
     * 同步阻塞执行指定任务，并在出现异常时按照既定策略进行重试
     *
     * @param task 需要执行的具体业务逻辑
     * @param <T>  任务期望返回的数据类型
     * @return 任务成功结束后的结果
     * @throws Exception 如果由于满足策略的重试尝试最终均告失败，则将最后抛出的异常抛给调用方
     */
    public <T> T execute(Callable<T> task) throws Exception {
        return executeInMemory(task);
    }

    /**
     * 纯编程式强一致性执行入口
     *
     * @param taskType 任务类型 (用于宕机后，Worker 知道由谁来恢复执行)
     * @param task     当前内存中实际要执行的任务逻辑
     */
    public <T> T execute(String taskType, Supplier<String> payloadSupplier, Callable<T> task) throws Exception {
        String intentId = null;
        String payload = null;

        if (durability == RetryDurability.STRONG_CONSISTENCY && backend != null) {
            try {
                payload = payloadSupplier.get();
                intentId = backend.saveIntent(taskType, payload);
            } catch (RetrySerializationException e) {
                throw new IllegalStateException("强一致性级别要求参数必须可序列化，但序列化失败", e);
            }
        }

        try {
            T result = executeInMemory(task);
            if (intentId != null) {
                String finalIntentId = intentId;
                CompletableFuture.runAsync(() -> backend.completeIntent(finalIntentId), cleanupExecutor);
            }
            return result;
        } catch (Throwable ex) {
            if (ex instanceof Error) {
                throw (Error) ex;
            }
            if (backend != null && durability != RetryDurability.MEMORY_ONLY && shouldFallbackToBackend()) {
                try {
                    long nextDelay = policy.getDelayMillis(getNextAttemptAfterInMemory());
                    if (payload == null) {
                        payload = payloadSupplier.get();
                    }
                    backend.submitForDelay(intentId, taskType, payload, nextDelay);
                    throw new RetryExhaustedException("内存重试耗尽，已转入分布式后台队列", ex);
                } catch (RetrySerializationException serializationEx) {
                    // 降级时发现无法序列化，放弃入队，避免毒药数据
                    RetryExhaustedException finalEx = new RetryExhaustedException("内存重试耗尽，且参数序列化失败导致无法转入后台队列", ex);
                    finalEx.addSuppressed(serializationEx);
                    throw finalEx;
                }
            }
            Throwable cause = RetryExceptionUtil.unwrap(ex);
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new RuntimeException(cause);
        }
    }

    private <T> T executeInMemory(Callable<T> task) throws Exception {
        int attempt = 1;
        while (true) {
            try {
                return task.call();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Throwable ex) {
                if (ex instanceof Error) {
                    throw (Error) ex;
                }
                Throwable cause = RetryExceptionUtil.unwrap(ex);
                if (!canRetryInMemory(attempt, cause)) {
                    if (cause instanceof Exception) {
                        throw (Exception) cause;
                    }
                    throw new RuntimeException(cause);
                }

                long delay = policy.getDelayMillis(attempt);
                if (delay > 0) {
                    Thread.sleep(delay);
                }
                attempt++;
            }
        }
    }

    private boolean canRetryInMemory(int attempt, Throwable cause) {
        if (!policy.canRetry(attempt, cause)) {
            return false;
        }
        return attempt < inMemoryAttempts;
    }

    private int getNextAttemptAfterInMemory() {
        return Math.min(inMemoryAttempts + 1,
                policy.getTotalAttempts() == -1 ? inMemoryAttempts + 1 : policy.getTotalAttempts());
    }

    private int resolveInMemoryAttempts(RetryPolicy policy, RetryDurability durability) {
        if (policy.getInMemoryAttempts() != null) {
            return policy.getInMemoryAttempts();
        }
        if (policy.getTotalAttempts() == -1) {
            return durability == RetryDurability.MEMORY_ONLY ? Integer.MAX_VALUE
                    : DEFAULT_IN_MEMORY_ATTEMPTS_FOR_PERSISTENCE;
        }
        if (durability == RetryDurability.MEMORY_ONLY) {
            return policy.getTotalAttempts();
        }
        return Math.min(DEFAULT_IN_MEMORY_ATTEMPTS_FOR_PERSISTENCE, policy.getTotalAttempts());
    }

    private boolean shouldFallbackToBackend() {
        return policy.getTotalAttempts() == -1 || inMemoryAttempts < policy.getTotalAttempts();
    }

    /**
     * 非阻塞式的异步重试执行方法
     * <p>
     * 不会占用当前工作线程进行睡眠，而是将后续的重试任务转交给给定的调度器，
     * 以实现极低的资源消耗和高并发执行能力。
     *
     * @param taskType        任务类型
     * @param payloadSupplier 任务快照数据
     * @param asyncTask       提供异步执行逻辑对象的供应型接口
     * @param scheduler       用于延迟安排重试执行的后台调度器
     * @param <T>             预期的异步返回值类型
     * @return 表示整个带有重试能力的异步任务抽象票据
     */
    public <T> CompletableFuture<T> executeAsync(
            String taskType, Supplier<String> payloadSupplier,
            Supplier<CompletableFuture<T>> asyncTask,
            ScheduledExecutorService scheduler) {
        String intentId;
        String payload = null;
        // 执行前预写日志 (WAL)，防止宕机
        if (durability == RetryDurability.STRONG_CONSISTENCY && backend != null) {
            try {
                payload = payloadSupplier.get();
                intentId = backend.saveIntent(taskType, payload);
            } catch (RetrySerializationException e) {
                throw new IllegalStateException("强一致性级别要求参数必须可序列化，但序列化失败", e);
            }
        } else {
            intentId = null;
        }

        CompletableFuture<T> promise = new CompletableFuture<>();
        attemptAsync(taskType, payloadSupplier, payload, intentId, asyncTask, scheduler, promise, 1);
        return promise;
    }

    private <T> void attemptAsync(
            String taskType, Supplier<String> payloadSupplier, String payload, String intentId,
            Supplier<CompletableFuture<T>> asyncTask,
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
                    handleAsyncFailure(taskType, payloadSupplier, payload, intentId, asyncTask, scheduler, promise,
                            attempt, ex);
                }
            });
        } catch (Throwable ex) {
            handleAsyncFailure(taskType, payloadSupplier, payload, intentId, asyncTask, scheduler, promise, attempt,
                    ex);
        }
    }

    private <T> void handleAsyncSuccess(String intentId, CompletableFuture<T> promise, T result) {
        // 内存执行成功，异步清理预写日志
        if (intentId != null) {
            CompletableFuture.runAsync(() -> backend.completeIntent(intentId), cleanupExecutor);
        }
        promise.complete(result);
    }

    private <T> void handleAsyncFailure(
            String taskType, Supplier<String> payloadSupplier, String payload, String intentId,
            Supplier<CompletableFuture<T>> asyncTask,
            ScheduledExecutorService scheduler,
            CompletableFuture<T> promise,
            int attempt,
            Throwable ex) {
        if (ex instanceof Error) {
            promise.completeExceptionally(ex);
            return;
        }

        Throwable cause = RetryExceptionUtil.unwrap(ex);

        if (canRetryInMemory(attempt, cause)) {
            long delay = policy.getDelayMillis(attempt);
            try {
                scheduler.schedule(
                        () -> attemptAsync(taskType, payloadSupplier, payload, intentId, asyncTask, scheduler, promise,
                                attempt + 1),
                        delay, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                // 如果调度失败（如线程池关闭），必须完成 promise 以免调用方挂起
                promise.completeExceptionally(e);
            }
        } else {
            // 内存重试彻底耗尽，降级到后端存储
            if (backend != null && durability != RetryDurability.MEMORY_ONLY && shouldFallbackToBackend()) {
                try {
                    long nextDelay = policy.getDelayMillis(getNextAttemptAfterInMemory());

                    String finalPayload = payload != null ? payload : payloadSupplier.get();
                    backend.submitForDelay(intentId, taskType, finalPayload, nextDelay);

                    promise.completeExceptionally(new RetryExhaustedException("内存重试耗尽，已转入分布式后台队列", cause));
                } catch (RetrySerializationException serializationEx) {
                    // 降级时发现无法序列化，放弃入队，避免毒药数据
                    RetryExhaustedException finalEx = new RetryExhaustedException("内存重试耗尽，且参数序列化失败导致无法转入后台队列", cause);
                    finalEx.addSuppressed(serializationEx);
                    promise.completeExceptionally(finalEx);
                }
            } else {
                promise.completeExceptionally(cause);
            }
        }
    }

    /**
     * Retryer 建造者
     */
    public static class Builder {
        private RetryPolicy policy;
        private RetryBackend backend;
        private RetryDurability durability;
        private Executor cleanupExecutor;

        public Builder policy(RetryPolicy policy) {
            this.policy = policy;
            return this;
        }

        public Builder backend(RetryBackend backend) {
            this.backend = backend;
            return this;
        }

        public Builder durability(RetryDurability durability) {
            this.durability = durability;
            return this;
        }

        public Builder cleanupExecutor(Executor cleanupExecutor) {
            this.cleanupExecutor = cleanupExecutor;
            return this;
        }

        public Retryer build() {
            if (policy == null) {
                throw new IllegalStateException("RetryPolicy 不能为空");
            }
            if (durability != null && durability != RetryDurability.MEMORY_ONLY && backend == null) {
                throw new IllegalStateException("配置了持久化重试级别 [" + durability + "]，但未提供 RetryBackend 实现！");
            }
            return new Retryer(this);
        }
    }
}