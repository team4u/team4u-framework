package com.team4u.framework.retry.inline;

import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.common.concurrent.ForegroundRetryLoop;
import com.team4u.framework.retry.common.util.RetryExceptionUtil;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 进程内重试客户端的默认实现。
 * <p>
 * 该类通过单例模式提供全局访问，实现了同步与异步的重试执行逻辑。
 * 它直接在内存中处理重试循环，同步退避休眠经 base ThreadUtil，异步延迟经 {@link ScheduledExecutorService} 调度。
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

        // 前台同步重试循环与 MANAGED 客户端共用 ForegroundRetryLoop：
        // INLINE 无前台次数限制，重试耗尽时抛出最终异常（异常形态与旧实现一致：
        // Exception 子类按 execute 的 throws Exception 签名原样抛出）
        try {
            return ForegroundRetryLoop.execute(policy, callable::call,
                    new ForegroundRetryLoop.Listener<T, T>() {
                        @Override
                        public T onSuccess(T result, int failedAttemptsSoFar) {
                            return result;
                        }

                        @Override
                        public T onRetryExhausted(Throwable cause, int failedAttemptsSoFar) {
                            throw asTransparentRuntimeException(cause);
                        }

                        @Override
                        public T onInterrupted(InterruptedException interrupted, int failedAttemptsSoFar) {
                            throw asTransparentRuntimeException(interrupted);
                        }

                        @Override
                        public T onForegroundBudgetExhausted(Throwable cause, int failedAttemptsSoFar) {
                            // 无前台次数限制时不会走到；防御性抛出
                            throw asTransparentRuntimeException(cause);
                        }

                        @Override
                        public int maxForegroundExecutions() {
                            return -1;
                        }
                    });
        } catch (TransparentFailure ex) {
            // unchecked 回调无法直接抛 checked 异常，经透明包装通道在此还原
            throw wrap(ex.getCause());
        }
    }

    /**
     * Listener 回调（unchecked 签名）无法直接抛出 checked 异常，
     * 用此私有透明通道包装后由 execute 解包
     */
    private static final class TransparentFailure extends RuntimeException {
        private TransparentFailure(Throwable cause) {
            super(cause);
        }
    }

    /**
     * 将最终失败原因经透明通道抛出，保留原始异常对象不丢失
     */
    private RuntimeException asTransparentRuntimeException(Throwable cause) {
        throw new TransparentFailure(cause);
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
     * @param currentAttempt 当前已失败的次数
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
     * @param failedAttemptsSoFar 当前已失败的次数
     * @param policy              重试策略
     * @param asyncTask           异步任务生成器
     * @param scheduler           调度器
     * @param resultFuture        最终 Future
     * @param ex                  本次尝试发生的异常
     */
    private <T> void handleAsyncFailure(
            int failedAttemptsSoFar,
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
        if (!policy.canRetry(failedAttemptsSoFar, cause)) {
            resultFuture.completeExceptionally(cause);
            return;
        }

        // 根据策略计算下一次尝试的延迟时间
        long delayMillis = policy.getDelayMillis(failedAttemptsSoFar);
        if (resultFuture.isDone()) {
            return;
        }
        try {
            if (delayMillis > 0) {
                // 利用调度器在指定的延迟时间后重新发起任务
                ScheduledFuture<?> scheduledFuture = scheduler.schedule(
                        () -> attemptAsync(
                                failedAttemptsSoFar,
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
                attemptAsync(
                        failedAttemptsSoFar,
                        policy,
                        asyncTask,
                        scheduler,
                        resultFuture,
                        inFlightFutureRef,
                        scheduledRetryRef);
            }
        } catch (Exception scheduleEx) {
            // 调度器本身出现异常（如已关闭）时，保留原始业务异常作为主异常，
            // 避免调度问题掩盖真实失败原因。
            cause.addSuppressed(scheduleEx);
            resultFuture.completeExceptionally(cause);
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
     * 包装 Throwable 为 Exception 实例。
     * <p>
     * execute 方法签名仅声明 Exception：Exception 及其子类（含
     * InterruptedException）原样抛出，仅对非 Exception 的 Throwable（如自定义
     * 直接继承 Throwable 的异常）做 RuntimeException 包装。
     */
    private Exception wrap(Throwable cause) {
        if (cause instanceof Exception) {
            return (Exception) cause;
        }
        return new RuntimeException(cause);
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
        return RetryExceptionUtil.unwrapAndRestoreInterrupt(ex);
    }
}
