package com.team4u.framework.flow;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * 执行回调并强制 deadline 的运行器，供 Operation/Policy 调用复用。
 * 超时则取消子 Cancellation 与任务；父取消时将自发中断转为取消结果。
 */
final class CallbackRunner {
    /** 回调执行结果：正常值、异常或超时标志三者互斥。 */
    static final class Result<T> {
        private final T value;
        private final Throwable failure;
        private final boolean timeout;

        public Result(T value, Throwable failure, boolean timeout) {
            this.value = value;
            this.failure = failure;
            this.timeout = timeout;
        }

        public T value() {
            return value;
        }

        public Throwable failure() {
            return failure;
        }

        public boolean timeout() {
            return timeout;
        }
    }

    private final Cancellation parent;
    private final ExecutorService executor;

    CallbackRunner(Cancellation parent, ExecutorService executor) {
        this.parent = parent;
        this.executor = executor;
    }

    /** 在子 Cancellation 与 deadline 约束下执行回调，返回超时/失败/成功结果。 */
    <T> Result<T> call(final Function<Cancellation, T> callback, Instant deadline) {
        if (deadline == null) return direct(callback, parent);
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isNegative() || remaining.isZero()) return new Result<T>(null, null, true);
        final Cancellation child = Cancellation.linked(parent);
        FutureTask<Result<T>> task = new FutureTask<Result<T>>(new Callable<Result<T>>() {
            @Override
            public Result<T> call() {
                if (child.isCancelled()) {
                    return new Result<T>(null,
                            new CancellationException("flow execution was cancelled"), false);
                }
                Thread worker = Thread.currentThread();
                child.attach(worker);
                try {
                    return direct(callback, child);
                } finally {
                    child.detach(worker);
                }
            }
        });
        try {
            try {
                executor.execute(task);
            } catch (RuntimeException rejected) {
                child.cancel();
                task.cancel(true);
                return new Result<T>(null, rejected, false);
            }
            return ManagedBlockers.get(task, remaining);
        } catch (TimeoutException timeout) {
            child.cancel();
            task.cancel(true);
            return new Result<T>(null, null, true);
        } catch (InterruptedException interrupted) {
            child.cancel();
            task.cancel(true);
            if (parent.isCancelled()) {
                Thread.interrupted();
                return new Result<T>(null,
                        new CancellationException("flow execution was cancelled"), false);
            }
            Thread.currentThread().interrupt();
            return new Result<T>(null, interrupted, false);
        } catch (ExecutionException execution) {
            Throwable cause = execution.getCause();
            if (cause instanceof Error) throw (Error) cause;
            return new Result<T>(null, cause, false);
        } finally {
            child.unlink();
        }
    }

    private static <T> Result<T> direct(Function<Cancellation, T> callback,
                                        Cancellation cancellation) {
        try {
            return new Result<T>(callback.apply(cancellation), null, false);
        } catch (Error error) {
            throw error;
        } catch (Throwable failure) {
            return new Result<T>(null, failure, false);
        }
    }
}
