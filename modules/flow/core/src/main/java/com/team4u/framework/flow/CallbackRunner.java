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
 * 策略回调安全受控执行器（Callback Runner）。
 *
 * <p>在执行策略的 {@link Policy#before} / {@link Policy#after} 等扩展点时，统一施加 Deadline 截止时间保护、
 * 取消信号级联传播以及受控异常包装。</p>
 *
 * @author team4u
 */
final class CallbackRunner {
    /**
     * 回调执行结果代数容器（正常返回值、异常原因或超时标志三者互斥）。
     *
     * @param <T> 回调返回值类型
     */
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

    /**
     * 在子 Cancellation 与 Deadline 约束下受控执行回调函数。
     *
     * @param callback 回调函数
     * @param deadline 截止时刻（为 null 时直接在当前线程同步执行）
     * @param <T>      返回值类型
     * @return 包含值、异常或超时标记的执行结果 {@link Result}
     */
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
