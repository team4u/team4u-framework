package com.team4u.framework.retry;

import com.team4u.framework.retry.backend.RetryBackend;
import com.team4u.framework.retry.backend.RetryCloseRequest;
import com.team4u.framework.retry.backend.RetryPayloadBuilder;
import com.team4u.framework.retry.concurrent.RetryExecutorManager;
import com.team4u.framework.retry.exception.RetryHandoffException;
import com.team4u.framework.retry.policy.RetryPolicy;
import com.team4u.framework.retry.recovery.RetryRecoveryPlanner;
import lombok.Builder;

import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * 重试执行引擎
 * <p>
 * 提供同步和非阻塞异步重试机制，支持内存模式下的快速重试以及与后端存储集成的持久化重试。
 * 该引擎解耦了重试策略、执行环境和后端持久化逻辑。
 */
public class Retryer {

    /**
     * 默认的持久化前内存重试次数
     */
    private static final int DEFAULT_IN_MEMORY_ATTEMPTS_FOR_PERSISTENCE = 2;

    /**
     * 重试恢复规划器，用于决定下一次重试动作
     */
    private static final RetryRecoveryPlanner PLANNER = new RetryRecoveryPlanner();

    /**
     * 重试策略配置，包含最大尝试次数、退避策略等
     */
    private final RetryPolicy policy;

    /**
     * 当前实例允许的内存中最大重试尝试次数
     */
    private final int localAttempts;

    /**
     * 持久化协调器，负责处理与后端存储相关的生命周期管理
     */
    private final RetryPersistenceCoordinator coordinator;

    /**
     * 构建重试执行引擎
     *
     * @param policy          重试策略，定义了何时重试以及重试间隔
     * @param retryBackend    重试后端持久化适配器，用于将重试任务持久化到外部存储
     * @param policyKey       策略唯一标识，用于持久化时匹配对应的配置
     * @param cleanupExecutor 清理任务执行器，用于异步处理后端任务的关闭或清理动作
     */
    @Builder
    private Retryer(RetryPolicy policy, RetryBackend retryBackend, String policyKey, Executor cleanupExecutor) {
        if (policy == null) {
            throw new IllegalStateException("RetryPolicy must not be null");
        }
        this.policy = policy;
        this.localAttempts = resolveLocalAttempts(policy, retryBackend);

        // 如果未提供清理执行器，则使用全局默认执行器
        Executor actualCleanupExecutor = cleanupExecutor != null ? cleanupExecutor
                : RetryExecutorManager.global().getCleanupExecutor();

        this.coordinator = new RetryPersistenceCoordinator(
                retryBackend, policyKey, policy.getMaxAttempts(), actualCleanupExecutor);
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
     * 根据策略和后端配置解析最大内存尝试次数
     *
     * @param policy             重试策略
     * @param persistenceAdapter 持久化适配器
     * @return 解析后的内存重试次数
     */
    private static int resolveLocalAttempts(RetryPolicy policy, RetryBackend persistenceAdapter) {
        // 如果没有持久化后端，则所有尝试都在内存中进行
        if (persistenceAdapter == null) {
            if (policy.getMaxAttempts() == -1) {
                return Integer.MAX_VALUE;
            }
            return policy.getMaxAttempts();
        }

        // 如果存在持久化后端，优先使用策略中定义的本地尝试次数，否则使用默认值
        int resolved = policy.getLocalAttempts() != null
                ? policy.getLocalAttempts()
                : DEFAULT_IN_MEMORY_ATTEMPTS_FOR_PERSISTENCE;

        // 本地重试次数不应超过策略定义的总最大尝试次数
        if (policy.getMaxAttempts() == -1) {
            return resolved;
        }
        return Math.min(resolved, policy.getMaxAttempts());
    }

    /**
     * 同步执行任务（仅支持内存模式）
     * <p>
     * 该方法要求引擎未配置持久化后端。
     *
     * @param task 待执行的任务
     * @param <T>  返回结果类型
     * @return 任务执行结果
     * @throws Exception 任务执行失败且重试耗尽后抛出的异常
     */
    public <T> T execute(Callable<T> task) throws Exception {
        if (coordinator.hasRetryBackend()) {
            throw new IllegalStateException(
                    "Retryer.execute(Callable) supports memory mode only. Use execute(taskType, payloadBuilder, task) "
                            + "or executeAsync(taskType, payloadBuilder, ...) when a persistence adapter is configured.");
        }
        return execute(null, null, task);
    }

    /**
     * 同步执行任务，支持持久化重试模式
     *
     * @param taskType       任务类型标识，用于持久化恢复
     * @param payloadBuilder 用于构建任务负载的生成器，在持久化时保存任务状态
     * @param task           待执行的任务
     * @param <T>            返回结果类型
     * @return 任务执行结果
     * @throws Exception 任务执行失败且无法继续重试时抛出的异常
     */
    public <T> T execute(String taskType, RetryPayloadBuilder payloadBuilder, Callable<T> task) throws Exception {
        RetryExecutionContext<T> context = new RetryExecutionContext<>(taskType, payloadBuilder);
        // 执行前的预处理，如初始化上下文状态
        coordinator.prepare(context);

        while (true) {
            try {
                T result = task.call();
                // 任务成功完成，关闭相关持久化任务
                coordinator.closeSucceeded(context);
                return result;
            } catch (Throwable ex) {
                // 处理任务失败逻辑，包括规划重试或移交后端
                handleFailure(context, ex);
            }
        }
    }

    /**
     * 处理同步执行中的失败情况
     */
    private void handleFailure(RetryExecutionContext<?> context, Throwable ex) throws Exception {
        Throwable cause = normalize(ex);
        // 更新执行进度和最后一次异常信息
        context.updateProgress(cause);
        // 保存当前执行进度到后端（如果配置了后端）
        coordinator.saveProgress(context);

        // 由规划器根据当前上下文决定下一步行动
        RetryRecoveryPlanner.Plan plan = PLANNER.plan(
                context, policy, localAttempts, coordinator.hasRetryBackend());

        switch (plan.getType()) {
            case RETRY_IN_MEMORY:
                // 内存重试，线程进入休眠后继续循环
                sleepQuietly(plan.getDelayMillis());
                return;
            case HANDOFF_TO_BACKEND:
                // 内存重试耗尽，将任务移交给后端异步处理
                coordinator.handoff(context, plan.getDelayMillis());
                throw new RetryHandoffException(
                        "In-memory retries exhausted; task has been handed over to persistence storage.",
                        cause);
            case CLOSE:
            default:
                // 任务彻底失败，关闭任务并记录原因
                coordinator.closeFailed(context, RetryCloseRequest.failed(plan.getReason(), plan.getErrorMessage()));
                throw wrap(cause);
        }
    }

    /**
     * 异步执行任务（仅支持内存模式）
     * <p>
     * 该方法要求引擎未配置持久化后端。
     *
     * @param asyncTask 返回 CompletableFuture 的异步任务供应者
     * @param scheduler 用于调度延迟重试的线程池
     * @param <T>       返回结果类型
     * @return 异步执行结果的 Promise
     */
    public <T> CompletableFuture<T> executeAsync(
            Supplier<CompletableFuture<T>> asyncTask,
            ScheduledExecutorService scheduler) {
        if (coordinator.hasRetryBackend()) {
            throw new IllegalStateException(
                    "Retryer.executeAsync(asyncTask, scheduler) supports memory mode only. "
                            + "Use executeAsync(taskType, payloadBuilder, ...) when a persistence adapter is configured.");
        }
        return executeAsync(null, null, asyncTask, scheduler);
    }

    /**
     * 异步执行任务，支持持久化重试模式
     *
     * @param taskType       任务类型标识，用于持久化恢复
     * @param payloadBuilder 用于构建任务负载的生成器
     * @param asyncTask      返回 CompletableFuture 的异步任务供应者
     * @param scheduler      用于调度延迟重试的线程池
     * @param <T>            返回结果类型
     * @return 异步执行结果的 Promise
     */
    public <T> CompletableFuture<T> executeAsync(
            String taskType,
            RetryPayloadBuilder payloadBuilder,
            Supplier<CompletableFuture<T>> asyncTask,
            ScheduledExecutorService scheduler) {
        RetryExecutionContext<T> context = new RetryExecutionContext<>(taskType, payloadBuilder);
        context.setPromise(new CompletableFuture<>());

        try {
            // 执行前的预处理
            coordinator.prepare(context);
            // 开启异步尝试流程
            attemptAsync(context, asyncTask, scheduler);
        } catch (Throwable ex) {
            // 在提交异步任务前如果发生异常，也视为一次失败处理
            handleAsyncFailure(context, asyncTask, scheduler, ex);
        }

        return context.getPromise();
    }

    /**
     * 发起一次异步任务尝试
     */
    private <T> void attemptAsync(
            RetryExecutionContext<T> context,
            Supplier<CompletableFuture<T>> asyncTask,
            ScheduledExecutorService scheduler) {
        try {
            CompletableFuture<T> future = asyncTask.get();
            if (future == null) {
                throw new NullPointerException("asyncTask.get() returned null");
            }
            // 注册回调处理任务结果
            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    // 任务成功，完成 Promise
                    coordinator.closeSucceeded(context);
                    context.getPromise().complete(result);
                } else {
                    // 任务失败，进入失败处理流程
                    handleAsyncFailure(context, asyncTask, scheduler, ex);
                }
            });
        } catch (Throwable ex) {
            handleAsyncFailure(context, asyncTask, scheduler, ex);
        }
    }

    /**
     * 处理异步执行中的失败情况
     */
    private <T> void handleAsyncFailure(
            RetryExecutionContext<T> context,
            java.util.function.Supplier<CompletableFuture<T>> asyncTask,
            ScheduledExecutorService scheduler,
            Throwable ex) {
        Throwable cause = normalize(ex);
        // 更新并保存进度
        context.updateProgress(cause);
        coordinator.saveProgress(context);

        // 获取恢复规划
        RetryRecoveryPlanner.Plan plan = PLANNER.plan(
                context, policy, localAttempts, coordinator.hasRetryBackend());

        switch (plan.getType()) {
            case RETRY_IN_MEMORY:
                try {
                    // 调度下一次延时重试
                    scheduler.schedule(
                            () -> attemptAsync(context, asyncTask, scheduler),
                            plan.getDelayMillis(), TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    context.getPromise().completeExceptionally(e);
                }
                break;
            case HANDOFF_TO_BACKEND:
                try {
                    // 移交给后端持久化重试
                    coordinator.handoff(context, plan.getDelayMillis());
                    context.getPromise().completeExceptionally(new RetryHandoffException(
                            "In-memory retries exhausted; task has been handed over to persistence storage.",
                            cause));
                } catch (Exception stateEx) {
                    context.getPromise().completeExceptionally(stateEx);
                }
                break;
            case CLOSE:
            default:
                // 彻底失败并关闭
                coordinator.closeFailed(context, RetryCloseRequest.failed(plan.getReason(), plan.getErrorMessage()));
                context.getPromise().completeExceptionally(cause);
                break;
        }
    }

    /**
     * 安静地休眠指定时间，处理中断异常
     */
    private void sleepQuietly(long delay) throws InterruptedException {
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                // 恢复中断状态并重新抛出，以便上层能感知并停止重试循环
                Thread.currentThread().interrupt();
                throw e;
            }
        }
    }

    /**
     * 标准化异常，剥离包装层，处理中断状态
     */
    private Throwable normalize(Throwable ex) {
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
     * 将异常包装为受检异常或运行时异常以便抛出
     */
    private Exception wrap(Throwable cause) {
        if (cause instanceof Exception) {
            return (Exception) cause;
        }
        return new RuntimeException(cause);
    }

}