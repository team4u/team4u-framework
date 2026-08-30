package com.team4u.framework.flow;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

/**
 * Operation 运行上下文，刻意只暴露元数据、幂等 invocationId 与取消信号。
 * {@link #await} 将 CompletionStage 同步阻塞为值，并在取消时抛 CancellationException。
 */
public interface OperationContext {
    Metadata metadata();

    String invocationId();

    Cancellation.Signal cancellation();

    default <T> T await(CompletionStage<T> stage) throws Exception {
        Objects.requireNonNull(stage, "stage must not be null");
        CompletableFuture<T> future = stage.toCompletableFuture();
        if (cancellation().isCancelled()) {
            future.cancel(true);
            throw new CancellationException("flow execution was cancelled");
        }
        try {
            T value = future.get();
            if (cancellation().isCancelled()) {
                throw new CancellationException("flow execution was cancelled");
            }
            return Objects.requireNonNull(value, "awaited value must not be null");
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (ExecutionException execution) {
            Throwable cause = execution.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException(cause);
        }
    }
}
