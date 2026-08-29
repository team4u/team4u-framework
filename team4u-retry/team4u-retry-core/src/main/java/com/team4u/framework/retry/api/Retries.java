package com.team4u.framework.retry.api;

import com.team4u.framework.base.util.StringUtil;
import com.team4u.framework.retry.common.concurrent.RetryExecutorManager;
import com.team4u.framework.retry.inline.DefaultInlineRetryClient;
import com.team4u.framework.retry.inline.InlineRetryClient;
import com.team4u.framework.retry.managed.client.ManagedRetryClient;
import com.team4u.framework.retry.managed.submit.RetryTaskSpec;
import lombok.Builder;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

/**
 * 重试统一门面。
 */
public final class Retries {

    private Retries() {
    }

    /**
     * 创建 INLINE 执行入口。
     *
     * @return 一个新的 INLINE 执行计划
     */
    public static InlineExecution inline() {
        return new InlineExecution(DefaultInlineRetryClient.getInstance());
    }

    /**
     * 创建 MANAGED 执行入口。
     * <p>
     * MANAGED 侧没有全局默认 client，因此托管入口直接要求显式传入
     * {@link ManagedRetryClient}。
     *
     * @param managedClient MANAGED 重试客户端
     * @return 一个绑定了指定客户端的 MANAGED 执行计划
     * @throws IllegalArgumentException 当 {@code managedClient} 为空时抛出
     */
    public static ManagedExecution.Builder managed(ManagedRetryClient managedClient) {
        return ManagedExecution.builder().managedClient(requireManagedClient(managedClient));
    }

    private static ManagedRetryClient requireManagedClient(ManagedRetryClient managedClient) {
        if (managedClient == null) {
            throw new IllegalArgumentException("ManagedRetryClient must not be null");
        }
        return managedClient;
    }

    private static boolean isBlank(String value) {
        return StringUtil.isBlank(value);
    }

    /**
     * INLINE 执行计划。
     */
    public static final class InlineExecution {
        private final InlineRetryClient inlineClient;
        private RetryPolicy policy;

        private InlineExecution(InlineRetryClient inlineClient) {
            this.inlineClient = inlineClient;
        }

        /**
         * 为当前执行计划设置重试策略。
         *
         * @param policy 重试策略
         * @return 当前执行计划本身
         * @throws IllegalArgumentException 当 {@code policy} 为空时抛出
         */
        public InlineExecution policy(RetryPolicy policy) {
            if (policy == null) {
                throw new IllegalArgumentException("RetryPolicy must not be null");
            }
            this.policy = policy;
            return this;
        }

        /**
         * 同步执行任务。
         * <p>
         * 该调用直接委托给现有 {@link InlineRetryClient}，不会改变 INLINE 模式语义。
         *
         * @param task 要执行的任务
         * @param <T>  任务返回值类型
         * @return 任务执行结果
         * @throws IllegalStateException    当未先配置 {@link RetryPolicy} 时抛出
         * @throws IllegalArgumentException 当 {@code task} 为空时抛出
         * @throws Exception                当重试耗尽或任务执行异常时抛出
         */
        public <T> T call(Callable<T> task) throws Exception {
            if (policy == null) {
                throw new IllegalStateException("RetryPolicy must be configured before calling task");
            }
            if (task == null) {
                throw new IllegalArgumentException("Task must not be null");
            }
            return inlineClient.execute(policy, task);
        }

        /**
         * 使用框架默认调度器异步执行任务。
         *
         * @param task 返回 {@link CompletableFuture} 的异步任务
         * @param <T>  任务返回值类型
         * @return 承载最终结果的异步结果对象
         * @throws IllegalStateException    当未先配置 {@link RetryPolicy} 时抛出
         * @throws IllegalArgumentException 当 {@code task} 为空时抛出
         */
        public <T> CompletableFuture<T> callAsync(Supplier<CompletableFuture<T>> task) {
            // 与现有 proxy/spring 行为保持一致，默认走全局调度器。
            return callAsync(task, RetryExecutorManager.global().getScheduler());
        }

        /**
         * 使用指定调度器异步执行任务。
         *
         * @param task      返回 {@link CompletableFuture} 的异步任务
         * @param scheduler 用于退避调度的调度器
         * @param <T>       任务返回值类型
         * @return 承载最终结果的异步结果对象
         * @throws IllegalStateException    当未先配置 {@link RetryPolicy} 时抛出
         * @throws IllegalArgumentException 当 {@code task} 或 {@code scheduler} 为空时抛出
         */
        public <T> CompletableFuture<T> callAsync(
                Supplier<CompletableFuture<T>> task,
                ScheduledExecutorService scheduler) {
            if (policy == null) {
                throw new IllegalStateException("RetryPolicy must be configured before calling task");
            }
            if (task == null) {
                throw new IllegalArgumentException("Task must not be null");
            }
            if (scheduler == null) {
                throw new IllegalArgumentException("ScheduledExecutorService must not be null");
            }
            return inlineClient.executeAsync(policy, task, scheduler);
        }
    }

    /**
     * MANAGED 执行计划。
     */
    @Builder(builderClassName = "Builder")
    public static final class ManagedExecution {
        /**
         * MANAGED 重试客户端。
         */
        private final ManagedRetryClient managedClient;
        /**
         * 恢复任务类型，对应底层 {@link RecoverySpec#getTaskType()}。
         */
        private final String taskType;
        /**
         * 业务幂等键。
         */
        private final String idempotencyKey;
        /**
         * 后台恢复所需的负载。
         */
        private final String payload;
        /**
         * 完整重试策略。
         * <p>
         * 当前 DSL 只接受完整的 {@link RetryPolicy}，不再提供分项策略拼装能力。
         */
        private final RetryPolicy policy;

        /**
         * 仅构建底层 {@link RetryTaskSpec}，不触发提交。
         *
         * @param task 要执行的任务
         * @param <T>  任务返回值类型
         * @return 底层任务规格对象
         * @throws IllegalStateException    当 taskType 或 idempotencyKey 不合法时抛出
         * @throws IllegalArgumentException 当 {@code task} 为空时抛出
         */
        public <T> RetryTaskSpec<T> toSpec(Callable<T> task) {
            validateInputs(task);
            // DSL 只是上层语义包装，最终仍回落到底层 RetryTaskSpec 模型。
            return RetryTaskSpec.<T>builder()
                    .idempotencyKey(idempotencyKey)
                    .executor(task)
                    .recovery(RecoverySpec.of(taskType, payload))
                    .policy(policy)
                    .build();
        }

        /**
         * 构建并提交 MANAGED 任务。
         *
         * @param task 要执行的任务
         * @param <T>  任务返回值类型
         * @return 托管提交结果
         * @throws IllegalStateException    当 taskType 或 idempotencyKey 不合法时抛出
         * @throws IllegalArgumentException 当 {@code task} 为空时抛出
         */
        public <T> ManagedSubmitResult<T> call(Callable<T> task) {
            // 统一入口不改变 MANAGED 原有结果语义，直接透传 ManagedSubmitResult。
            return managedClient.submit(toSpec(task));
        }

        private <T> void validateInputs(Callable<T> task) {
            if (isBlank(taskType)) {
                throw new IllegalStateException("Managed taskType must not be blank");
            }
            if (isBlank(idempotencyKey)) {
                throw new IllegalStateException("Managed idempotencyKey must not be blank");
            }
            if (policy == null) {
                throw new IllegalStateException("Managed RetryPolicy must be configured before calling task");
            }
            if (policy.getForegroundMaxRetries() == null) {
                throw new IllegalStateException(
                        "Managed RetryPolicy must configure foregroundMaxRetries before calling task");
            }
            if (task == null) {
                throw new IllegalArgumentException("Task must not be null");
            }
        }

        public static class Builder {
            /**
             * 构建并提交 MANAGED 任务。
             *
             * @param task 要执行的任务
             * @param <T>  任务返回值类型
             * @return 托管提交结果
             */
            public <T> ManagedSubmitResult<T> call(Callable<T> task) {
                return build().call(task);
            }

            /**
             * 仅构建底层 {@link RetryTaskSpec}，不触发提交。
             *
             * @param task 要执行的任务
             * @param <T>  任务返回值类型
             * @return 底层任务规格对象
             */
            public <T> RetryTaskSpec<T> toSpec(Callable<T> task) {
                return build().toSpec(task);
            }
        }
    }
}
