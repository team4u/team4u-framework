package com.team4u.framework.retry.client;

import com.team4u.framework.retry.policy.RetryPolicy;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

/**
 * 仅包含内存进程内重试处理能力的客户端。
 */
public interface InlineRetryClient {

    /**
     * 同步执行重试动作
     *
     * @param policy   重试策略
     * @param callable 基于进程内的业务回调方法
     * @param <T>      返回值泛型
     * @return 最终返回结果
     * @throws Exception 若达到最大重试次数或碰到终止异常则原样抛出
     */
    <T> T execute(RetryPolicy policy, Callable<T> callable) throws Exception;

    /**
     * 异步执行重试动作
     *
     * @param policy    重试策略
     * @param asyncTask 基于进程内的异步业务回调
     * @param scheduler 定时调度器，用于处理退避重试线程
     * @param <T>       返回值泛型
     * @return Future
     */
    <T> CompletableFuture<T> executeAsync(
            RetryPolicy policy,
            Supplier<CompletableFuture<T>> asyncTask,
            ScheduledExecutorService scheduler);
}
