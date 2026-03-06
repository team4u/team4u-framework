package com.team4u.framework.retry;

import cn.hutool.crypto.digest.DigestUtil;
import com.team4u.framework.retry.concurrent.RetryExecutorManager;
import com.team4u.framework.retry.exception.RetrySerializationException;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.IntFunction;

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

    /**
     * 决定下一次重试阶段的类型
     */
    public enum RetryDecisionType {
        /** 在内存中继续重试 */
        RETRY_IN_MEMORY,
        /** 转移到后端队列进行重试 */
        HANDOFF_TO_BACKEND,
        /** 策略明确终止或者次数已经完全耗尽，不再重试 */
        FAIL_TERMINAL
    }

    /**
     * 通过建造者创建重试执行器实例。
     *
     * @param builder 配置建造者
     */
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
     * 纯编程式强一致性执行入口
     *
     * @param taskType       任务类型 (用于宕机后，Worker 知道由谁来恢复执行)
     * @param payloadBuilder 任务快照构建器，参数为尝试次数
     * @param task           当前内存中实际要执行的任务逻辑
     * @param <T>            返回值类型
     * @return 执行成功后的结果
     * @throws Exception 业务异常或重试最终失败异常
     */
    public <T> T execute(String taskType, IntFunction<String> payloadBuilder, Callable<T> task) throws Exception {
        IntentContext intentContext = prepareIntent(taskType, payloadBuilder);
        int attempt = 1;
        while (true) {
            try {
                T result = task.call();
                if (intentContext.intentId != null) {
                    String finalIntentId = intentContext.intentId;
                    CompletableFuture.runAsync(() -> backend.completeIntent(finalIntentId), cleanupExecutor);
                }
                return result;
            } catch (Throwable ex) {
                Throwable cause = normalizeSyncFailure(ex);
                RetryDecisionType decision = evaluateDecision(attempt, cause);

                if (decision == RetryDecisionType.FAIL_TERMINAL) {
                    if (intentContext.intentId != null) {
                        String finalIntentId = intentContext.intentId;
                        CompletableFuture.runAsync(() -> backend.markTerminalFailure(finalIntentId, cause),
                                cleanupExecutor);
                    }
                    if (cause instanceof Exception) {
                        throw (Exception) cause;
                    }
                    throw new RuntimeException(cause);
                } else if (decision == RetryDecisionType.HANDOFF_TO_BACKEND) {
                    try {
                        throw enqueueToBackend(intentContext.intentId, taskType, payloadBuilder, attempt, cause);
                    } catch (RetrySerializationException serializationEx) {
                        if (intentContext.intentId != null) {
                            String finalIntentId = intentContext.intentId;
                            CompletableFuture.runAsync(() -> backend.markTerminalFailure(finalIntentId, cause),
                                    cleanupExecutor);
                        }
                        RetryExhaustedException finalEx = new RetryExhaustedException(
                                "In-memory retries exhausted, and argument serialization failed so task cannot be enqueued to backend.",
                                cause);
                        finalEx.addSuppressed(serializationEx);
                        throw finalEx;
                    }
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
     * 评估当前尝试失败后，下一步的重试决策。
     *
     * @param attempt 当前尝试次数（从 1 开始）
     * @param cause   失败原因
     * @return 重试决策类型
     */
    private RetryDecisionType evaluateDecision(int attempt, Throwable cause) {
        if (!policy.canRetry(attempt, cause)) {
            return RetryDecisionType.FAIL_TERMINAL;
        }
        if (attempt < inMemoryAttempts) {
            return RetryDecisionType.RETRY_IN_MEMORY;
        }
        if (backend != null && durability != RetryDurability.MEMORY_ONLY && shouldFallbackToBackend()) {
            return RetryDecisionType.HANDOFF_TO_BACKEND;
        }
        return RetryDecisionType.FAIL_TERMINAL;
    }

    /**
     * 计算内存阶段耗尽后，后端队列应使用的下一次尝试序号。
     *
     * @return 下一次尝试序号
     */
    private int getNextAttemptAfterInMemory() {
        return Math.min(inMemoryAttempts + 1,
                policy.getMaxAttempts() == -1 ? inMemoryAttempts + 1 : policy.getMaxAttempts());
    }

    /**
     * 解析内存阶段最大尝试次数。
     *
     * @param policy     重试策略
     * @param durability 持久化级别
     * @return 内存阶段最大尝试次数
     */
    private int resolveInMemoryAttempts(RetryPolicy policy, RetryDurability durability) {
        if (policy.getInMemoryAttempts() != null) {
            return policy.getInMemoryAttempts();
        }
        if (policy.getMaxAttempts() == -1) {
            return durability == RetryDurability.MEMORY_ONLY ? Integer.MAX_VALUE
                    : DEFAULT_IN_MEMORY_ATTEMPTS_FOR_PERSISTENCE;
        }
        if (durability == RetryDurability.MEMORY_ONLY) {
            return policy.getMaxAttempts();
        }
        return Math.min(DEFAULT_IN_MEMORY_ATTEMPTS_FOR_PERSISTENCE, policy.getMaxAttempts());
    }

    /**
     * 判断内存重试耗尽后是否还应降级到后端继续重试。
     *
     * @return true 表示允许降级
     */
    private boolean shouldFallbackToBackend() {
        return policy.getMaxAttempts() == -1 || inMemoryAttempts < policy.getMaxAttempts();
    }

    /**
     * 获取提交到后端队列使用的 intentId。
     * <p>
     * 若已有 intentId 则直接复用，否则基于 taskType + payload 生成稳定兜底 ID。
     *
     * @param intentId 原 intentId
     * @param taskType 任务类型
     * @param payload  任务快照
     * @return 可用于后端提交的 intentId
     */
    private String ensureIntentIdForBackend(String intentId, String taskType, String payload) {
        if (intentId != null && !intentId.isEmpty()) {
            return intentId;
        }
        // payload 可能很长，hash 全量即可；最终只取前 32 个 hex 做短 id
        String base = taskType + "\n" + (payload == null ? "" : payload);
        String sha256 = DigestUtil.sha256Hex(base);

        // taskType 里可能有 '.' '/' 等，建议做轻量 sanitize，避免日志/存储不友好
        String safeTaskType = taskType == null ? "unknown" : taskType.replaceAll("[^a-zA-Z0-9_-]", "_");

        return "rtryh-" + safeTaskType + "-" + sha256.substring(0, 32);
    }

    /**
     * 非阻塞式的异步重试执行方法
     * <p>
     * 不会占用当前工作线程进行睡眠，而是将后续的重试任务转交给给定的调度器，
     * 以实现极低的资源消耗和高并发执行能力。
     *
     * @param taskType       任务类型
     * @param payloadBuilder 任务快照数据
     * @param asyncTask      提供异步执行逻辑对象的供应型接口
     * @param scheduler      用于延迟安排重试执行的后台调度器
     * @param <T>            预期的异步返回值类型
     * @return 表示整个带有重试能力的异步任务抽象票据
     */
    public <T> CompletableFuture<T> executeAsync(
            String taskType,
            IntFunction<String> payloadBuilder,
            java.util.function.Supplier<CompletableFuture<T>> asyncTask,
            ScheduledExecutorService scheduler) {
        IntentContext intentContext = prepareIntent(taskType, payloadBuilder);

        CompletableFuture<T> promise = new CompletableFuture<>();
        attemptAsync(taskType, payloadBuilder, intentContext.intentId, asyncTask, scheduler, promise, 1);
        return promise;
    }

    /**
     * 执行一次异步尝试并根据结果决定完成、重试或降级。
     *
     * @param taskType       任务类型
     * @param payloadBuilder 任务快照构建器
     * @param intentId       意图 ID
     * @param asyncTask      异步任务供应器
     * @param scheduler      调度器
     * @param promise        对外返回的结果承诺
     * @param attempt        当前尝试次数
     * @param <T>            返回值类型
     */
    private <T> void attemptAsync(
            String taskType, IntFunction<String> payloadBuilder, String intentId,
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
     * 处理异步执行成功路径。
     *
     * @param intentId 意图 ID
     * @param promise  对外结果承诺
     * @param result   结果值
     * @param <T>      返回值类型
     */
    private <T> void handleAsyncSuccess(String intentId, CompletableFuture<T> promise, T result) {
        // 内存执行成功，异步清理预写日志
        if (intentId != null) {
            CompletableFuture.runAsync(() -> backend.completeIntent(intentId), cleanupExecutor);
        }
        promise.complete(result);
    }

    /**
     * 处理异步执行失败路径，包括内存重试和后端降级。
     *
     * @param taskType       任务类型
     * @param payloadBuilder 任务快照构建器
     * @param intentId       意图 ID
     * @param asyncTask      异步任务供应器
     * @param scheduler      调度器
     * @param promise        对外结果承诺
     * @param attempt        当前尝试次数
     * @param ex             原始异常
     * @param <T>            返回值类型
     */
    private <T> void handleAsyncFailure(
            String taskType, IntFunction<String> payloadBuilder, String intentId,
            java.util.function.Supplier<CompletableFuture<T>> asyncTask,
            ScheduledExecutorService scheduler,
            CompletableFuture<T> promise,
            int attempt,
            Throwable ex) {
        Throwable cause = normalizeAsyncFailure(ex);
        if (cause instanceof Error) {
            promise.completeExceptionally(cause);
            return;
        }
        if (cause instanceof InterruptedException) {
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
                // 如果调度失败（如线程池关闭），必须完成 promise 以免调用方挂起
                promise.completeExceptionally(e);
            }
        } else if (decision == RetryDecisionType.HANDOFF_TO_BACKEND) {
            try {
                promise.completeExceptionally(enqueueToBackend(intentId, taskType, payloadBuilder, attempt, cause));
            } catch (RetrySerializationException serializationEx) {
                if (intentId != null) {
                    String finalIntentId = intentId;
                    CompletableFuture.runAsync(() -> backend.markTerminalFailure(finalIntentId, cause),
                            cleanupExecutor);
                }
                RetryExhaustedException finalEx = new RetryExhaustedException(
                        "In-memory retries exhausted, and argument serialization failed so task cannot be enqueued to backend.",
                        cause);
                finalEx.addSuppressed(serializationEx);
                promise.completeExceptionally(finalEx);
            }
        } else {
            if (intentId != null) {
                String finalIntentId = intentId;
                CompletableFuture.runAsync(() -> backend.markTerminalFailure(finalIntentId, cause), cleanupExecutor);
            }
            promise.completeExceptionally(cause);
        }
    }

    /**
     * 在强一致性模式下预写 intent；非强一致性模式直接返回空上下文。
     *
     * @param taskType       任务类型
     * @param payloadBuilder 任务快照构建器
     * @return intent 上下文
     */
    private IntentContext prepareIntent(String taskType, IntFunction<String> payloadBuilder) {
        if (durability != RetryDurability.AT_LEAST_ONCE_DURABLE || backend == null) {
            return new IntentContext(null);
        }
        try {
            // attempt=0 约定为“执行前快照”，用于 WAL 预写
            String payload = payloadBuilder.apply(0);
            String intentId = backend.saveIntent(taskType, payload);
            if (intentId == null || intentId.isEmpty()) {
                throw new IllegalStateException(
                        "AT_LEAST_ONCE_DURABLE requires non-null intentId from backend.saveIntent()");
            }
            return new IntentContext(intentId);
        } catch (RetrySerializationException e) {
            throw new IllegalStateException(
                    "AT_LEAST_ONCE_DURABLE requires serializable arguments, but serialization failed.", e);
        }
    }

    /**
     * 将任务提交至后端延迟队列，并返回用于通知调用方的耗尽异常。
     *
     * @param intentId       意图 ID
     * @param taskType       任务类型
     * @param payloadBuilder 任务快照构建器
     * @param payloadAttempt 构建 payload 所用的尝试次数
     * @param cause          原始失败原因
     * @return 表示已降级入队的异常
     * @throws RetrySerializationException 参数序列化失败时抛出
     */
    private RetryExhaustedException enqueueToBackend(
            String intentId, String taskType,
            IntFunction<String> payloadBuilder,
            int payloadAttempt, Throwable cause)
            throws RetrySerializationException {
        // 下一次尝试由后端 worker 执行，延迟按“内存阶段之后的首次尝试”计算
        long nextDelay = policy.getDelayMillis(getNextAttemptAfterInMemory());
        // payloadAttempt 由调用方传入：同步为 inMemoryAttempts，异步为当前 attempt
        String payload = payloadBuilder.apply(payloadAttempt);
        String submitIntentId = ensureIntentIdForBackend(intentId, taskType, payload);
        backend.submitForDelay(submitIntentId, taskType, payload, nextDelay);
        return new RetryExhaustedException("In-memory retries exhausted; task has been handed over to backend queue.",
                cause);
    }

    /**
     * 归一化同步路径异常：透传 Error，中断恢复标记并抛出，其余返回 unwrap 后原因。
     *
     * @param ex 原始异常
     * @return unwrap 后的失败原因
     * @throws InterruptedException 中断异常
     */
    private Throwable normalizeSyncFailure(Throwable ex) throws InterruptedException {
        // 同步语义：中断必须立即恢复中断标记并向上抛出
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
     * 归一化异步路径异常：透传 Error，中断恢复标记，其余返回 unwrap 后原因。
     *
     * @param ex 原始异常
     * @return 归一化后的异常
     */
    private Throwable normalizeAsyncFailure(Throwable ex) {
        // 异步语义：归一化为 cause 并以 exceptionally 完成 promise，不直接抛出
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
     * intent 上下文，仅用于在线程内传递预写后的 intentId。
     */
    private static final class IntentContext {
        private final String intentId;

        /**
         * 创建 intent 上下文。
         *
         * @param intentId 意图 ID
         */
        private IntentContext(String intentId) {
            this.intentId = intentId;
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

        /**
         * 设置重试策略（必填）。
         *
         * @param policy 重试策略
         * @return 当前建造者
         */
        public Builder policy(RetryPolicy policy) {
            this.policy = policy;
            return this;
        }

        /**
         * 设置持久化后端实现。
         *
         * @param backend 重试后端
         * @return 当前建造者
         */
        public Builder backend(RetryBackend backend) {
            this.backend = backend;
            return this;
        }

        /**
         * 设置持久化级别。
         *
         * @param durability 持久化级别
         * @return 当前建造者
         */
        public Builder durability(RetryDurability durability) {
            this.durability = durability;
            return this;
        }

        /**
         * 设置用于异步清理 intent 的执行器。
         * <p>
         * 若未设置，将使用全局默认清理线程池。
         *
         * @param cleanupExecutor 清理执行器
         * @return 当前建造者
         */
        public Builder cleanupExecutor(Executor cleanupExecutor) {
            this.cleanupExecutor = cleanupExecutor;
            return this;
        }

        /**
         * 构建 Retryer 实例。
         * <p>
         * 约束：
         * <p>
         * 1) {@link #policy(RetryPolicy)} 必须先设置；
         * <p>
         * 2) 当 durability 不是 {@code MEMORY_ONLY} 时，必须提供 {@link #backend(RetryBackend)}。
         *
         * @return 构建完成的 Retryer
         * @throws IllegalStateException 当配置不满足约束时抛出
         */
        public Retryer build() {
            if (policy == null) {
                throw new IllegalStateException("RetryPolicy must not be null");
            }
            if (durability != null && durability != RetryDurability.MEMORY_ONLY && backend == null) {
                throw new IllegalStateException(
                        "RetryBackend is required when durability is set to [" + durability + "]");
            }
            return new Retryer(this);
        }
    }
}
