package com.team4u.framework.retry.api;

import com.team4u.framework.retry.common.concurrent.RetryExecutorManager;
import com.team4u.framework.retry.inline.DefaultInlineRetryClient;
import com.team4u.framework.retry.inline.InlineRetryClient;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

/**
 * INLINE 重试门面。
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

}
