package com.team4u.framework.retry.inline;

import com.team4u.framework.retry.api.RetryPolicy;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

/**
 * 进程内重试客户端接口。
 * <p>
 * 该客户端专门用于处理应用进程内的内存级重试逻辑，不涉及外部存储的持久化。
 * 它适用于那些对延迟敏感、执行时间较短且即使应用重启丢失重试状态也可接受的任务。
 */
public interface InlineRetryClient {

    /**
     * 同步执行重试操作。
     * <p>
     * 该方法会阻塞当前线程，直到任务成功执行、达到最大重试次数或遇到不可重试的异常。
     *
     * @param policy   重试策略，定义了何时重试、重试次数及退避时间
     * @param callable 业务回调逻辑，包含具体的业务执行代码
     * @param <T>      业务执行结果的类型
     * @return 业务逻辑执行成功后的返回结果
     * @throws Exception 如果重试耗尽仍未成功，或遇到策略定义的不可重试异常，则抛出对应的异常
     */
    <T> T execute(RetryPolicy policy, Callable<T> callable) throws Exception;

    /**
     * 异步执行重试操作。
     * <p>
     * 该方法不会阻塞当前线程，任务的后续重试将通过指定的调度器异步执行。
     *
     * @param policy    重试策略，定义了何时重试、重试次数及退避时间
     * @param asyncTask 提供 CompletableFuture 的异步任务函数
     * @param scheduler 定时调度服务，用于处理具有延迟的退避重试
     * @param <T>       业务执行结果的类型
     * @return 包含最终执行结果的 CompletableFuture 实例
     */
    <T> CompletableFuture<T> executeAsync(
            RetryPolicy policy,
            Supplier<CompletableFuture<T>> asyncTask,
            ScheduledExecutorService scheduler);
}