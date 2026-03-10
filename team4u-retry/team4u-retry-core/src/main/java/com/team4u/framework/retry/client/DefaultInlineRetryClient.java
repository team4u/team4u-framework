package com.team4u.framework.retry.client;

import com.team4u.framework.retry.policy.RetryPolicy;
import com.team4u.framework.retry.util.RetryExceptionUtil;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 进程内重试客户端的默认实现。
 * <p>
 * 该类通过单例模式提供全局访问，实现了同步与异步的重试执行逻辑。
 * 它直接在内存中处理重试循环，并利用 {@link Thread#sleep} 或 {@link ScheduledExecutorService} 处理退避延迟。
 */
public class DefaultInlineRetryClient implements InlineRetryClient {

    private static final DefaultInlineRetryClient INSTANCE = new DefaultInlineRetryClient();

    /**
     * 获取全局唯一的单例实例。
     *
     * @return 默认的进程内重试客户端
     */
    public static DefaultInlineRetryClient getInstance() {
        return INSTANCE;
    }

    @Override
    public <T> T execute(RetryPolicy policy, Callable<T> callable) throws Exception {
        // 校验重试策略，进程内模式目前不支持前台重试次数配置，应统一使用最大尝试次数
        if (policy.getForegroundMaxRetries() != null) {
            throw new IllegalArgumentException(
                    "Inline mode does not support foregroundMaxRetries, please use maxRetries instead");
        }

        int attempts = 0;
        while (true) {
            try {
                // 执行具体的业务逻辑回调
                return callable.call();
            } catch (Throwable ex) {
                // 统一异常形态，处理中断及 Error 类型的严重错误
                Throwable cause = normalize(ex);

                // Error 类型（如 OutOfMemoryError）属于严重的系统级异常，
                // 不应进入重试循环，直接向上抛出
                if (cause instanceof Error) {
                    throw (Error) cause;
                }

                attempts++;

                // 根据策略判断当前已尝试次数和异常类型是否允许继续重试
                if (!policy.canRetry(attempts, cause)) {
                    throw wrap(cause);
                }

                // 获取当前重试批次对应的退避延迟时间，并进行休眠等待
                long delayMillis = policy.getDelayMillis(attempts);
                sleepQuietly(delayMillis);
            }
        }
    }

    @Override
    public <T> CompletableFuture<T> executeAsync(RetryPolicy policy, Supplier<CompletableFuture<T>> asyncTask,
                                                 ScheduledExecutorService scheduler) {
        // 异步执行模式下的重试策略校验
        if (policy.getForegroundMaxRetries() != null) {
            CompletableFuture<T> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalArgumentException(
                    "Inline async mode does not support foregroundMaxRetries, please use maxRetries instead"));
            return future;
        }

        CompletableFuture<T> resultFuture = new CompletableFuture<>();
        AtomicReference<CompletableFuture<?>> inFlightFutureRef = new AtomicReference<CompletableFuture<?>>();
        AtomicReference<ScheduledFuture<?>> scheduledRetryRef = new AtomicReference<ScheduledFuture<?>>();
        registerCancellationBridge(resultFuture, inFlightFutureRef, scheduledRetryRef);
        // 发起初次异步尝试
        attemptAsync(0, policy, asyncTask, scheduler, resultFuture, inFlightFutureRef, scheduledRetryRef);
        return resultFuture;
    }

    /**
     * 递归执行异步任务尝试。
     *
     * @param currentAttempt 当前已重试的次数
     * @param policy         重试策略
     * @param asyncTask      异步任务生成器
     * @param scheduler      执行退避调度的调度器
     * @param resultFuture   承载最终结果的 Future 对象
     */
    private <T> void attemptAsync(
            int currentAttempt,
            RetryPolicy policy,
            Supplier<CompletableFuture<T>> asyncTask,
            ScheduledExecutorService scheduler,
            CompletableFuture<T> resultFuture,
            AtomicReference<CompletableFuture<?>> inFlightFutureRef,
            AtomicReference<ScheduledFuture<?>> scheduledRetryRef) {
        if (resultFuture.isDone()) {
            return;
        }
        scheduledRetryRef.set(null);
        try {
            CompletableFuture<T> future = asyncTask.get();
            if (future == null) {
                throw new NullPointerException("Async task callback (asyncTask.get()) returned null");
            }
            inFlightFutureRef.set(future);
            if (resultFuture.isDone()) {
                inFlightFutureRef.compareAndSet(future, null);
                cancelFuture(future);
                return;
            }
            // 监听异步任务执行完成事件
            future.whenComplete((result, ex) -> {
                inFlightFutureRef.compareAndSet(future, null);
                if (resultFuture.isDone()) {
                    return;
                }
                if (ex == null) {
                    // 任务成功完成，填充结果到最终的 Future
                    resultFuture.complete(result);
                } else {
                    // 任务执行失败，进入失败处理流程
                    handleAsyncFailure(
                            currentAttempt + 1,
                            policy,
                            asyncTask,
                            scheduler,
                            resultFuture,
                            inFlightFutureRef,
                            scheduledRetryRef,
                            ex);
                }
            });
        } catch (Throwable ex) {
            // 获取异步任务过程中发生异常（如 asyncTask.get() 本身抛错）
            if (resultFuture.isDone()) {
                return;
            }
            inFlightFutureRef.set(null);
            handleAsyncFailure(
                    currentAttempt + 1,
                    policy,
                    asyncTask,
                    scheduler,
                    resultFuture,
                    inFlightFutureRef,
                    scheduledRetryRef,
                    ex);
        }
    }

    /**
     * 处理异步任务失败时的逻辑，决定是否继续调度下一次重试。
     *
     * @param attempts     当前尝试的次数
     * @param policy       重试策略
     * @param asyncTask    异步任务生成器
     * @param scheduler    调度器
     * @param resultFuture 最终 Future
     * @param ex           本次尝试发生的异常
     */
    private <T> void handleAsyncFailure(
            int attempts,
            RetryPolicy policy,
            Supplier<CompletableFuture<T>> asyncTask,
            ScheduledExecutorService scheduler,
            CompletableFuture<T> resultFuture,
            AtomicReference<CompletableFuture<?>> inFlightFutureRef,
            AtomicReference<ScheduledFuture<?>> scheduledRetryRef,
            Throwable ex) {
        if (resultFuture.isDone()) {
            return;
        }
        Throwable cause = normalize(ex);
        if (cause instanceof Error) {
            resultFuture.completeExceptionally(cause);
            return;
        }

        // 如果重试策略判断不再重试，则将异常传播到最终 Future 中
        if (!policy.canRetry(attempts, cause)) {
            resultFuture.completeExceptionally(cause);
            return;
        }

        // 根据策略计算下一次尝试的延迟时间
        long delayMillis = policy.getDelayMillis(attempts);
        if (resultFuture.isDone()) {
            return;
        }
        try {
            if (delayMillis > 0) {
                // 利用调度器在指定的延迟时间后重新发起任务
                ScheduledFuture<?> scheduledFuture = scheduler.schedule(
                        () -> attemptAsync(
                                attempts,
                                policy,
                                asyncTask,
                                scheduler,
                                resultFuture,
                                inFlightFutureRef,
                                scheduledRetryRef),
                        delayMillis,
                        TimeUnit.MILLISECONDS);
                replaceScheduledRetry(scheduledRetryRef, scheduledFuture);
                if (resultFuture.isDone() && scheduledRetryRef.compareAndSet(scheduledFuture, null)) {
                    cancelScheduledFuture(scheduledFuture);
                }
            } else {
                // 如果没有延迟，则立即发起下一次尝试
                attemptAsync(attempts, policy, asyncTask, scheduler, resultFuture, inFlightFutureRef, scheduledRetryRef);
            }
        } catch (Exception scheduleEx) {
            // 调度器本身出现异常（如已关闭）时，直接终结任务
            resultFuture.completeExceptionally(scheduleEx);
        }
    }

    private void registerCancellationBridge(
            CompletableFuture<?> resultFuture,
            AtomicReference<CompletableFuture<?>> inFlightFutureRef,
            AtomicReference<ScheduledFuture<?>> scheduledRetryRef) {
        resultFuture.whenComplete((ignored, ex) -> {
            if (resultFuture.isCancelled()) {
                cancelFuture(inFlightFutureRef.getAndSet(null));
                cancelScheduledFuture(scheduledRetryRef.getAndSet(null));
            }
        });
    }

    private void replaceScheduledRetry(
            AtomicReference<ScheduledFuture<?>> scheduledRetryRef,
            ScheduledFuture<?> scheduledFuture) {
        ScheduledFuture<?> previous = scheduledRetryRef.getAndSet(scheduledFuture);
        if (previous != null && previous != scheduledFuture) {
            cancelScheduledFuture(previous);
        }
    }

    private void cancelFuture(CompletableFuture<?> future) {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }

    private void cancelScheduledFuture(ScheduledFuture<?> future) {
        if (future != null && !future.isDone()) {
            future.cancel(false);
        }
    }

    /**
     * 安静地让当前线程休眠指定的时间。
     *
     * @param delay 延迟时间（毫秒）
     * @throws InterruptedException 如果休眠期间线程被中断
     */
    private void sleepQuietly(long delay) throws InterruptedException {
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                // 重置线程中断状态并抛出异常，让调用方决定如何处理
                Thread.currentThread().interrupt();
                throw e;
            }
        }
    }

    /**
     * 规范化异常。
     * <p>
     * 将各种包装后的异常还原为其原始原因，并正确维护线程中断状态。
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
     * 包装 Throwable 为 Exception 实例。
     */
    private Exception wrap(Throwable cause) {
        if (cause instanceof Exception) {
            return (Exception) cause;
        }
        return new RuntimeException(cause);
    }
}
