package com.team4u.framework.flow;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 执行 Invoke 节点：调用 Operation 并处理超时、取消与异常。
 * 无 deadline 时直接在当前线程同步执行；有 deadline 时在 executor 上执行并带时限等待。
 */
final class InvocationRunner {
    private final String flowId;
    private final int flowVersion;
    private final String executionId;
    private final Cancellation cancellation;
    private final FlowObserver observer;
    private final ExecutorService executor;

    InvocationRunner(String flowId, int flowVersion, String executionId,
                     Cancellation cancellation, FlowObserver observer, ExecutorService executor) {
        this.flowId = flowId;
        this.flowVersion = flowVersion;
        this.executionId = executionId;
        this.cancellation = cancellation;
        this.observer = observer;
        this.executor = executor;
    }

    /** 执行单个 Invoke 节点：根据是否有 deadline 选择同步或带时限执行。 */
    Outcome<?> invoke(PlanNode.Invoke node, Object entry, Instant deadline) {
        long started = System.nanoTime();
        Outcome<?> outcome;
        if (deadline == null) {
            outcome = execute(node, entry, cancellation);
        } else {
            Duration remaining = Duration.between(Instant.now(), deadline);
            if (remaining.isNegative() || remaining.isZero()) outcome = timeout();
            else outcome = timed(node, entry, remaining);
        }
        if (!cancellation.isCancelled()) {
            Map<String, String> attrs = new LinkedHashMap<String, String>();
            attrs.put("outcome", outcome.kind().name());
            attrs.put("durationNanos", Long.toString(System.nanoTime() - started));
            String code = diagnosticCode(outcome);
            if (!code.isEmpty()) {
                attrs.put("code", code);
            }
            event(FlowObserver.Type.NODE_COMPLETED, node.descriptor(), attrs);
        }
        return outcome;
    }

    /**
     * 带时限执行：在 executor 上运行 Operation，主线程限时等待。超时/中断会取消子任务，
     * 取消时重抛 CancellationException，其他异常转为失败 Outcome。
     */
    private Outcome<?> timed(final PlanNode.Invoke node, final Object entry, Duration remaining) {
        final Cancellation child = Cancellation.linked(cancellation);
        FutureTask<Outcome<?>> task = new FutureTask<Outcome<?>>(new Callable<Outcome<?>>() {
            @Override
            public Outcome<?> call() {
                if (child.isCancelled()) {
                    throw new CancellationException("flow execution was cancelled");
                }
                Thread worker = Thread.currentThread();
                child.attach(worker);
                try {
                    return execute(node, entry, child);
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
                return failed("EXECUTOR_REJECTED",
                        rejected.getMessage() == null ? "Operation execution rejected" : rejected.getMessage());
            }
            return ManagedBlockers.get(task, remaining);
        } catch (TimeoutException timeout) {
            child.cancel();
            task.cancel(true);
            return timeout();
        } catch (InterruptedException interrupted) {
            child.cancel();
            task.cancel(true);
            if (cancellation.isCancelled()) {
                Thread.interrupted();
                throw new CancellationException("flow execution was cancelled");
            }
            Thread.currentThread().interrupt();
            return failed("OPERATION_INTERRUPTED", "Operation thread was interrupted");
        } catch (ExecutionException execution) {
            Throwable cause = execution.getCause();
            if (cause instanceof Error) throw (Error) cause;
            return failure(cause);
        } finally {
            child.unlink();
        }
    }

    /**
     * 实际调用 Operation：project 派生入参，execute 执行，Accepted 再经 merge 合并原状态与输出。
     * InterruptedException 在已取消时转为 CancellationException，其他异常转为失败 Outcome。
     */
    @SuppressWarnings("unchecked")
    private Outcome<?> execute(PlanNode.Invoke node, Object entry, Cancellation signal) {
        try {
            Object input = Objects.requireNonNull(node.project().apply(entry),
                    "projected input must not be null");
            Context context = new Context(node.descriptor(), signal);
            Outcome<Object> outcome = Objects.requireNonNull(
                    ((Operation<Object, Object>) node.operation().instance())
                            .execute(context, input),
                    "operation outcome must not be null");
            if (outcome instanceof Outcome.Accepted) {
                Outcome.Accepted<Object> accepted = (Outcome.Accepted<Object>) outcome;
                Object merged = Objects.requireNonNull(
                    node.merge().apply(entry, accepted.value()),
                    "merged output must not be null");
                return Outcome.accepted(merged);
            }
            return outcome;
        } catch (CancellationException cancelled) {
            if (signal.isCancelled()) throw cancelled;
            return failure(cancelled);
        } catch (Exception exception) {
            if (exception instanceof InterruptedException && signal.isCancelled()) {
                Thread.interrupted();
                throw new CancellationException("flow execution was cancelled");
            }
            return failure(exception);
        }
    }

    private Outcome<?> failure(Throwable throwable) {
        String message = throwable.getMessage();
        return failed("OPERATION_EXCEPTION", throwable.getClass().getName()
                + (message == null || message.trim().isEmpty() ? "" : ": " + message));
    }

    private static Outcome<?> timeout() {
        return failed("TIMEOUT", "Flow scope deadline elapsed");
    }

    private static Outcome<?> failed(String code, String message) {
        return Outcome.failed(Failure.of(code, message));
    }

    private static String diagnosticCode(Outcome<?> outcome) {
        if (outcome instanceof Outcome.Rejected) {
            return ((Outcome.Rejected<?>) outcome).reason().code();
        } else if (outcome instanceof Outcome.Skipped) {
            return ((Outcome.Skipped<?>) outcome).reason().code();
        } else if (outcome instanceof Outcome.Failed) {
            return ((Outcome.Failed<?>) outcome).failure().code();
        }
        return "";
    }

    private void event(FlowObserver.Type type, NodeDescriptor descriptor,
                       Map<String, String> attributes) {
        Metadata metadata = new Metadata(flowId, flowVersion, executionId,
                descriptor.path(), descriptor.label());
        try {
            observer.onEvent(new FlowObserver.Event(type, Instant.now(), metadata,
                    descriptor, attributes));
        } catch (RuntimeException ignored) {
            // Observers cannot alter execution.
        }
    }

    /**
     * OperationContext 实现：暴露元数据、稳定的 invocationId（用于外部幂等写入）与取消信号。
     */
    private final class Context implements OperationContext {
        private final NodeDescriptor descriptor;
        private final Cancellation signal;

        private Context(NodeDescriptor descriptor, Cancellation signal) {
            this.descriptor = descriptor;
            this.signal = signal;
        }

        @Override public Metadata metadata() {
            return new Metadata(flowId, flowVersion, executionId,
                    descriptor.path(), descriptor.label());
        }

        @Override public String invocationId() {
            return flowId + ":" + flowVersion + ":" + executionId + ":" + descriptor.path();
        }

        @Override public Cancellation.Signal cancellation() {
            return signal.signal();
        }
    }
}
