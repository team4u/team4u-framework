package com.team4u.framework.retry.client;

import com.team4u.framework.retry.RetryExceptionUtil;
import com.team4u.framework.retry.policy.RetryPolicy;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class DefaultInlineRetryClient implements InlineRetryClient {

    private static final DefaultInlineRetryClient INSTANCE = new DefaultInlineRetryClient();

    public static DefaultInlineRetryClient getInstance() {
        return INSTANCE;
    }

    @Override
    public <T> T execute(RetryPolicy policy, Callable<T> callable) throws Exception {
        if (policy.getForegroundAttempts() != null) {
            throw new IllegalArgumentException(
                    "INLINE mode does not support foregroundAttempts. Configure maxAttempts instead.");
        }

        int attempts = 0;
        while (true) {
            try {
                return callable.call();
            } catch (Throwable ex) {
                Throwable cause = normalize(ex);

                // Error 类型（如 OutOfMemoryError）属于严重的系统级异常，
                // 不应进入重试循环，直接向上抛出
                if (cause instanceof Error) {
                    throw (Error) cause;
                }

                attempts++;

                if (!policy.canRetry(attempts, cause)) {
                    throw wrap(cause);
                }

                long delayMillis = policy.getDelayMillis(attempts);
                sleepQuietly(delayMillis);
            }
        }
    }

    @Override
    public <T> CompletableFuture<T> executeAsync(RetryPolicy policy, Supplier<CompletableFuture<T>> asyncTask,
            ScheduledExecutorService scheduler) {
        if (policy.getForegroundAttempts() != null) {
            CompletableFuture<T> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalArgumentException(
                    "INLINE mode does not support foregroundAttempts. Configure maxAttempts instead."));
            return future;
        }

        CompletableFuture<T> resultFuture = new CompletableFuture<>();
        attemptAsync(0, policy, asyncTask, scheduler, resultFuture);
        return resultFuture;
    }

    private <T> void attemptAsync(
            int currentAttempt,
            RetryPolicy policy,
            Supplier<CompletableFuture<T>> asyncTask,
            ScheduledExecutorService scheduler,
            CompletableFuture<T> resultFuture) {
        try {
            CompletableFuture<T> future = asyncTask.get();
            if (future == null) {
                throw new NullPointerException("asyncTask.get() returned null");
            }
            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    resultFuture.complete(result);
                } else {
                    handleAsyncFailure(currentAttempt + 1, policy, asyncTask, scheduler, resultFuture, ex);
                }
            });
        } catch (Throwable ex) {
            handleAsyncFailure(currentAttempt + 1, policy, asyncTask, scheduler, resultFuture, ex);
        }
    }

    private <T> void handleAsyncFailure(
            int attempts,
            RetryPolicy policy,
            Supplier<CompletableFuture<T>> asyncTask,
            ScheduledExecutorService scheduler,
            CompletableFuture<T> resultFuture,
            Throwable ex) {
        Throwable cause = normalize(ex);

        if (!policy.canRetry(attempts, cause)) {
            resultFuture.completeExceptionally(cause);
            return;
        }

        long delayMillis = policy.getDelayMillis(attempts);
        try {
            if (delayMillis > 0) {
                scheduler.schedule(() -> attemptAsync(attempts, policy, asyncTask, scheduler, resultFuture),
                        delayMillis, TimeUnit.MILLISECONDS);
            } else {
                attemptAsync(attempts, policy, asyncTask, scheduler, resultFuture);
            }
        } catch (Exception scheduleEx) {
            resultFuture.completeExceptionally(scheduleEx);
        }
    }

    private void sleepQuietly(long delay) throws InterruptedException {
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }
    }

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

    private Exception wrap(Throwable cause) {
        if (cause instanceof Exception) {
            return (Exception) cause;
        }
        return new RuntimeException(cause);
    }
}
