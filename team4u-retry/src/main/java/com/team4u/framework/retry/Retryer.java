package com.team4u.framework.retry;

import com.team4u.framework.lease.LeaseAdminService;
import com.team4u.framework.lease.LeaseBackend;
import com.team4u.framework.lease.LeasePublishRequest;
import com.team4u.framework.lease.LeaseProducer;
import com.team4u.framework.retry.lease.RetryLeaseQueues;
import com.team4u.framework.retry.concurrent.RetryExecutorManager;
import com.team4u.framework.retry.exception.RetrySerializationException;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 重试执行引擎
 * <p>
 * 提供同步和非阻塞异步重试机制，支持内存重试及后端持久化重试。
 */
public class Retryer {

    private static final int DEFAULT_IN_MEMORY_ATTEMPTS_FOR_PERSISTENCE = 2;
    private static final long PREPARED_INTENT_DELAY_MILLIS = 3650L * 24L * 60L * 60L * 1000L;

    /**
     * 重试策略，定义重试次数、延迟等规则
     */
    private final RetryPolicy policy;
    /**
     * 重试持久化后端（当前接入了 LeaseBackend 实现分布式任务保障）
     */
    private final LeaseProducer producer;
    private final LeaseAdminService adminService;
    /**
     * 当前进程内允许执行的最大尝试次数
     */
    private final int localAttempts;
    /**
     * 用于执行清理任务（如取消后端任务）的线程池
     */
    private final Executor cleanupExecutor;

    /**
     * 私有构造函数，通过建造者模式实例化
     *
     * @param builder 配置建造者
     */
    private Retryer(Builder builder) {
        this.policy = builder.policy;
        this.producer = builder.producer;
        this.adminService = builder.adminService;
        this.localAttempts = resolveLocalAttempts(policy, producer);
        this.cleanupExecutor = builder.cleanupExecutor != null ? builder.cleanupExecutor
                : RetryExecutorManager.global().getCleanupExecutor();
    }

    /**
     * 获取建造者实例
     *
     * @return 建造者对象
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 根据策略初始化重试引擎
     *
     * @param policy 重试策略
     * @return 重试引擎实例
     */
    public static Retryer with(RetryPolicy policy) {
        return builder().policy(policy).build();
    }

    /**
     * 同步执行重试任务
     * <p>
     * 仅支持内存模式。若重试次数耗尽，则抛出最后一次执行的异常。
     *
     * @param task 业务逻辑任务
     * @param <T>  返回类型
     * @return 执行结果
     * @throws Exception 业务异常或重试失败异常
     */
    public <T> T execute(Callable<T> task) throws Exception {
        if (producer != null) {
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
                        // noinspection BusyWait
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
     * 内存异步重试
     *
     * @param asyncTask 异步任务提供者
     * @param scheduler 调度器
     * @param <T>       返回类型
     * @return 异步结果
     */
    public <T> CompletableFuture<T> executeAsync(
            java.util.function.Supplier<CompletableFuture<T>> asyncTask,
            ScheduledExecutorService scheduler) {
        if (producer != null) {
            throw new IllegalStateException(
                    "Retryer.executeAsync(asyncTask, scheduler) supports memory mode only. "
                            + "Use executeAsync(taskType, payloadBuilder, ...) when a backend is configured.");
        }

        CompletableFuture<T> promise = new CompletableFuture<>();
        attemptAsync(null, null, null, asyncTask, scheduler, promise, 1);
        return promise;
    }

    /**
     * 具备持久化保障的同步执行入口
     *
     * @param taskType       任务类型，用于故障恢复后的处理器定位
     * @param payloadBuilder 任务快照构建器
     * @param task           执行逻辑
     * @param <T>            返回类型
     * @return 执行结果
     * @throws Exception 业务异常或重试失败异常
     */
    public <T> T execute(String taskType, RetryPayloadBuilder payloadBuilder, Callable<T> task) throws Exception {
        IntentContext intentContext = prepareIntent(taskType, payloadBuilder);
        int attempt = 1;
        while (true) {
            try {
                T result = task.call();
                if (intentContext.intentId != null && adminService != null) {
                    String finalIntentId = intentContext.intentId;
                    CompletableFuture.runAsync(() -> adminService.cancel(finalIntentId), cleanupExecutor);
                }
                return result;
            } catch (Throwable ex) {
                Throwable cause = normalizeSyncFailure(ex);
                RetryDecisionType decision = evaluateDecision(attempt, cause);

                if (decision == RetryDecisionType.FAIL_TERMINAL) {
                    if (intentContext.intentId != null && adminService != null) {
                        String finalIntentId = intentContext.intentId;
                        CompletableFuture.runAsync(() -> adminService.cancel(finalIntentId), cleanupExecutor);
                    }
                    if (cause instanceof Exception) {
                        throw (Exception) cause;
                    }
                    throw new RuntimeException(cause);
                } else if (decision == RetryDecisionType.HANDOFF_TO_BACKEND) {
                    throw enqueueToBackend(intentContext.intentId, cause);
                }

                long delay = policy.getDelayMillis(attempt);
                if (delay > 0) {
                    try {
                        // noinspection BusyWait
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
     * 评估重试决策
     *
     * @param attempt 当前尝试次数
     * @param cause   失败原因
     * @return 重试决策类型
     */
    private RetryDecisionType evaluateDecision(int attempt, Throwable cause) {
        if (!policy.canRetry(attempt, cause)) {
            return RetryDecisionType.FAIL_TERMINAL;
        }
        if (attempt < localAttempts) {
            return RetryDecisionType.RETRY_IN_MEMORY;
        }
        if (producer != null && adminService != null && shouldFallbackToBackend()) {
            return RetryDecisionType.HANDOFF_TO_BACKEND;
        }
        return RetryDecisionType.FAIL_TERMINAL;
    }

    /**
     * 获取内存阶段结束后的下一次重试序号
     *
     * @return 尝试序号
     */
    private int getNextAttemptAfterInMemory() {
        return Math.min(localAttempts + 1,
                policy.getMaxAttempts() == -1 ? localAttempts + 1 : policy.getMaxAttempts());
    }

    /**
     * 解析本地阶段最大尝试次数
     *
     * @param policy  重试策略
     * @param backend 持久化后端
     * @return 本地尝试次数
     */
    private int resolveLocalAttempts(RetryPolicy policy, LeaseProducer producer) {
        if (producer == null) {
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

    /**
     * 判断是否允许降级至后端继续重试
     *
     * @return 允许降级返回 true
     */
    private boolean shouldFallbackToBackend() {
        return policy.getMaxAttempts() == -1 || localAttempts < policy.getMaxAttempts();
    }

    /**
     * 非阻塞异步重试入口
     * <p>
     * 通过调度器延迟任务执行，避免阻塞工作线程。
     *
     * @param taskType       任务类型
     * @param payloadBuilder 任务快照构建器
     * @param asyncTask      异步任务供应器
     * @param scheduler      调度器
     * @param <T>            返回类型
     * @return 异步结果凭据
     */
    public <T> CompletableFuture<T> executeAsync(
            String taskType,
            RetryPayloadBuilder payloadBuilder,
            java.util.function.Supplier<CompletableFuture<T>> asyncTask,
            ScheduledExecutorService scheduler) {
        IntentContext intentContext = prepareIntent(taskType, payloadBuilder);

        CompletableFuture<T> promise = new CompletableFuture<>();
        attemptAsync(taskType, payloadBuilder, intentContext.intentId, asyncTask, scheduler, promise, 1);
        return promise;
    }

    /**
     * 执行单次异步尝试并处理结果
     *
     * @param taskType       任务类型
     * @param payloadBuilder 任务快照构建器
     * @param intentId       意图 ID
     * @param asyncTask      异步任务供应器
     * @param scheduler      调度器
     * @param promise        异步结果凭据
     * @param attempt        当前尝试次数
     * @param <T>            返回类型
     */
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

    /**
     * 处理异步执行成功场景
     *
     * @param intentId 意图 ID
     * @param promise  异步结果凭据
     * @param result   成功结果
     * @param <T>      返回类型
     */
    private <T> void handleAsyncSuccess(String intentId, CompletableFuture<T> promise, T result) {
        if (intentId != null) {
            if (adminService != null) {
                CompletableFuture.runAsync(() -> adminService.cancel(intentId), cleanupExecutor);
            }
        }
        promise.complete(result);
    }

    /**
     * 处理异步执行失败场景
     *
     * @param taskType       任务类型
     * @param payloadBuilder 任务快照构建器
     * @param intentId       意图 ID
     * @param asyncTask      异步任务供应器
     * @param scheduler      调度器
     * @param promise        异步结果凭据
     * @param attempt        当前尝试次数
     * @param ex             执行异常
     * @param <T>            返回类型
     */
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
        } else if (decision == RetryDecisionType.HANDOFF_TO_BACKEND) {
            try {
                promise.completeExceptionally(enqueueToBackend(intentId, cause));
            } catch (IllegalStateException stateEx) {
                promise.completeExceptionally(stateEx);
            }
        } else {
            if (intentId != null) {
                if (adminService != null) {
                    CompletableFuture.runAsync(() -> adminService.cancel(intentId), cleanupExecutor);
                }
            }
            promise.completeExceptionally(cause);
        }
    }

    /**
     * 预写意图日志
     *
     * @param taskType       任务类型
     * @param payloadBuilder 任务快照构建器
     * @return 意图上下文
     */
    private IntentContext prepareIntent(String taskType, RetryPayloadBuilder payloadBuilder) {
        if (producer == null) {
            return new IntentContext(null);
        }
        try {
            String payload = payloadBuilder.build(RetryPayloadContext.prepareIntent());
            String intentId = producer.publish(LeasePublishRequest.builder()
                    .queue(RetryLeaseQueues.DEFAULT_RECOVERY_QUEUE)
                    .taskType(taskType)
                    .payload(payload)
                    .delayMillis(PREPARED_INTENT_DELAY_MILLIS)
                    .build());
            if (intentId == null || intentId.isEmpty()) {
                throw new IllegalStateException(
                        "Persistent retry requires non-null taskId from backend.publish()");
            }
            return new IntentContext(intentId);
        } catch (RetrySerializationException e) {
            throw new IllegalStateException(
                    "Persistent retry requires serializable arguments, but serialization failed.", e);
        }
    }

    /**
     * 将 prepared intent 重新调度到后端重试队列
     *
     * @param intentId 意图 ID
     * @param cause    原始失败原因
     * @return 重试耗尽异常，标记降级成功
     */
    private RetryExhaustedException enqueueToBackend(String intentId, Throwable cause) {
        long nextDelay = policy.getDelayMillis(getNextAttemptAfterInMemory());
        if (intentId == null || intentId.isEmpty()) {
            throw new IllegalStateException("Persistent retry handoff requires a prepared intent id.");
        }
        adminService.reschedule(intentId, nextDelay);
        return new RetryExhaustedException("In-memory retries exhausted; task has been handed over to backend queue.",
                cause);
    }

    /**
     * 归一化同步异常
     *
     * @param ex 原始异常
     * @return 归一化后的异常原因
     * @throws InterruptedException 中断异常
     */
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

    /**
     * 归一化异步异常
     *
     * @param ex 原始异常
     * @return 归一化后的异常
     */
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
         * 内存重试
         */
        RETRY_IN_MEMORY,
        /**
         * 降级至后端
         */
        HANDOFF_TO_BACKEND,
        /**
         * 终止执行
         */
        FAIL_TERMINAL
    }

    /**
     * 意图上下文，用于线程内传递意图标识
     */
    private static final class IntentContext {
        private final String intentId;

        private IntentContext(String intentId) {
            this.intentId = intentId;
        }
    }

    /**
     * Retryer 建造者
     */
        public static class Builder {
        private RetryPolicy policy;
        private LeaseProducer producer;
        private LeaseAdminService adminService;
        private Executor cleanupExecutor;

        /**
         * 设置重试策略
         *
         * @param policy 策略实例
         * @return 当前建造者
         */
        public Builder policy(RetryPolicy policy) {
            this.policy = policy;
            return this;
        }

        /**
         * 设置重试持久化后端实现
         *
         * @param backend 后端实例（集成自 team4u-lease）
         * @return 当前建造者
         */
        public Builder producer(LeaseProducer producer) {
            this.producer = producer;
            return this;
        }

        public Builder adminService(LeaseAdminService adminService) {
            this.adminService = adminService;
            return this;
        }

        public Builder backend(LeaseBackend backend) {
            this.producer = backend;
            this.adminService = backend;
            return this;
        }

        /**
         * 设置清理任务执行器
         *
         * @param cleanupExecutor 执行器
         * @return 当前建造者
         */
        public Builder cleanupExecutor(Executor cleanupExecutor) {
            this.cleanupExecutor = cleanupExecutor;
            return this;
        }

        /**
         * 构建 Retryer 实例
         *
         * @return 重试引擎实例
         */
        public Retryer build() {
            if (policy == null) {
                throw new IllegalStateException("RetryPolicy must not be null");
            }
            return new Retryer(this);
        }
    }
}
