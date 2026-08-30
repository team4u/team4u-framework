package com.team4u.framework.flow;

import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;

/**
 * 逻辑 Flow 的不可变 Local 投影。提供同步与异步的 run/resume 入口；
 * 每个 LocalExecutable 拥有唯一 identity，用于校验 Suspension 归属。
 */
public final class LocalExecutable<I, O> {
    private final Compiler.Compiled compiled;
    private final FlowObserver observer;
    private final ExecutorService workerExecutor;
    /** 用于校验 Suspension 归属的私有标识。 */
    private final Object identity = new Object();

    LocalExecutable(Compiler.Compiled compiled, FlowObserver observer, ExecutorService workerExecutor) {
        this.compiled = Objects.requireNonNull(compiled, "compiled must not be null");
        this.observer = Objects.requireNonNull(observer, "observer must not be null");
        this.workerExecutor = Objects.requireNonNull(workerExecutor, "workerExecutor must not be null");
        validateWorkerExecutor(compiled, this.workerExecutor);
    }

    public LocalExecutable<I, O> withExecutor(ExecutorService workerExecutor) {
        return new LocalExecutable<I, O>(compiled, observer,
                workerExecutor != null ? workerExecutor : ForkJoinPool.commonPool());
    }

    public FlowResult<O> run(I input) {
        return run(input, Cancellation.create());
    }

    public FlowResult<O> run(I input, Cancellation cancellation) {
        Objects.requireNonNull(input, "input must not be null");
        cancellation = Objects.requireNonNull(cancellation, "cancellation must not be null");
        String executionId = UUID.randomUUID().toString();
        MachineState state = new MachineState(compiled.root(), executionId, input);
        event(FlowObserver.Type.FLOW_STARTED, executionId,
                compiled.root().descriptor(), Collections.emptyMap());
        return drive(state, cancellation);
    }

    public CompletionStage<FlowResult<O>> runAsync(I input) {
        return runAsync(input, Cancellation.create(), ForkJoinPool.commonPool());
    }

    public CompletionStage<FlowResult<O>> runAsync(I input, Cancellation cancellation) {
        return runAsync(input, cancellation, ForkJoinPool.commonPool());
    }

    public CompletionStage<FlowResult<O>> runAsync(I input, ExecutorService dispatcher) {
        return runAsync(input, Cancellation.create(), dispatcher);
    }

    public CompletionStage<FlowResult<O>> runAsync(final I input, final Cancellation cancellation, ExecutorService dispatcher) {
        Objects.requireNonNull(cancellation, "cancellation must not be null");
        ExecutorService dispatchExec = dispatcher != null ? dispatcher : ForkJoinPool.commonPool();
        validateDispatcherNotStarvingWorker(dispatchExec);
        return async(new ThrowingSupplier<FlowResult<O>>() {
            @Override
            public FlowResult<O> get() {
                return run(input, cancellation);
            }
        }, dispatchExec);
    }

    public <R> FlowResult<O> resume(Suspension<O> suspension,
                                    ResumePoint<R> point, R signal) {
        return resume(suspension, point, signal, Cancellation.create());
    }

    /**
     * 恢复挂起的执行：校验 Suspension 归属与未被消费，装入恢复信号后继续驱动。
     */
    public <R> FlowResult<O> resume(Suspension<O> suspension, ResumePoint<R> point,
                                    R signal, Cancellation cancellation) {
        Objects.requireNonNull(suspension, "suspension must not be null");
        Objects.requireNonNull(point, "point must not be null");
        Objects.requireNonNull(signal, "resume signal must not be null");
        cancellation = Objects.requireNonNull(cancellation, "cancellation must not be null");
        if (!suspension.belongsTo(identity)) {
            throw new IllegalArgumentException("Suspension belongs to another LocalExecutable");
        }
        if (suspension.consumed()) {
            throw new IllegalStateException("Suspension was already consumed");
        }
        MachineState state = suspension.state();
        if (state.lifecycle != MachineState.Lifecycle.SUSPENDED) {
            throw new IllegalStateException("Suspension is not suspended");
        }
        if (!point.name().equals(state.awaitingPoint)) {
            throw new IllegalArgumentException("ResumePoint does not match suspension");
        }
        if (!suspension.consume()) {
            throw new IllegalStateException("Suspension was already consumed");
        }
        state.lifecycle = MachineState.Lifecycle.ACTIVE;
        state.pendingSignal = signal;
        return drive(state, cancellation);
    }

    public <R> CompletionStage<FlowResult<O>> resumeAsync(
            Suspension<O> suspension, ResumePoint<R> point, R signal) {
        return resumeAsync(suspension, point, signal, Cancellation.create(), ForkJoinPool.commonPool());
    }

    public <R> CompletionStage<FlowResult<O>> resumeAsync(
            Suspension<O> suspension, ResumePoint<R> point, R signal,
            Cancellation cancellation) {
        return resumeAsync(suspension, point, signal, cancellation, ForkJoinPool.commonPool());
    }

    public <R> CompletionStage<FlowResult<O>> resumeAsync(
            Suspension<O> suspension, ResumePoint<R> point, R signal,
            ExecutorService dispatcher) {
        return resumeAsync(suspension, point, signal, Cancellation.create(), dispatcher);
    }

    public <R> CompletionStage<FlowResult<O>> resumeAsync(
            final Suspension<O> suspension, final ResumePoint<R> point, final R signal,
            final Cancellation cancellation, ExecutorService dispatcher) {
        Objects.requireNonNull(cancellation, "cancellation must not be null");
        ExecutorService dispatchExec = dispatcher != null ? dispatcher : ForkJoinPool.commonPool();
        validateDispatcherNotStarvingWorker(dispatchExec);
        return async(new ThrowingSupplier<FlowResult<O>>() {
            @Override
            public FlowResult<O> get() {
                return resume(suspension, point, signal, cancellation);
            }
        }, dispatchExec);
    }

    private void validateDispatcherNotStarvingWorker(ExecutorService dispatcher) {
        if (dispatcher == workerExecutor && !(dispatcher instanceof ForkJoinPool) && compiled.hasNestedWorkerTasks()) {
            throw new IllegalArgumentException(
                    "Dangerous executor configuration: runAsync dispatcher and worker executor cannot be the same non-ForkJoinPool "
                            + "when flow contains parallel or timeout operations (causes thread starvation deadlock)");
        }
    }

    private static void validateWorkerExecutor(Compiler.Compiled compiled, ExecutorService worker) {
        if (compiled.requiresCompensatingWorker() && !(worker instanceof ForkJoinPool)) {
            throw new IllegalArgumentException(
                    "Flow contains nested parallel branches or parallel branch timeouts requiring worker thread compensation. "
                            + "Worker executor must be a ForkJoinPool (or default commonPool) to prevent thread pool starvation deadlock.");
        }
    }

    /**
     * 驱动 SerialMachine 并把 MachineResult 转为面向用户的 FlowResult。
     */
    @SuppressWarnings("unchecked")
    private FlowResult<O> drive(MachineState state, Cancellation cancellation) {
        SerialMachine machine = new SerialMachine(compiled.root(), "local", 0, state,
                cancellation, observer, workerExecutor);
        MachineResult result = machine.drive();
        switch (result.lifecycle()) {
            case COMPLETED:
                event(FlowObserver.Type.FLOW_COMPLETED, state.executionId,
                        compiled.root().descriptor(), Collections.singletonMap(
                                "outcome", result.outcome().kind().name()));
                return FlowResult.completed((Outcome<O>) result.outcome());
            case SUSPENDED:
                return FlowResult.suspended(new Suspension<O>(identity, state));
            case CANCELLED:
                event(FlowObserver.Type.FLOW_CANCELLED, state.executionId,
                        compiled.root().descriptor(), Collections.emptyMap());
                return FlowResult.cancelled(state.executionId);
            case ACTIVE:
            default:
                throw new IllegalStateException(
                        "Local execution cannot return an active wait");
        }
    }

    private void event(FlowObserver.Type type, String executionId,
                       NodeDescriptor descriptor, java.util.Map<String, String> attributes) {
        try {
            observer.onEvent(new FlowObserver.Event(type, Instant.now(),
                    new Metadata("local", 0, executionId, descriptor.path(), descriptor.label()),
                    descriptor, attributes));
        } catch (RuntimeException ignored) {
            // Observers cannot alter execution.
        }
    }

    /** 在执行器上运行 supplier，以 CompletableFuture 暴露结果，异常会传播到 future。 */
    private static <T> CompletionStage<T> async(final ThrowingSupplier<T> supplier, ExecutorService exec) {
        final CompletableFuture<T> future = new CompletableFuture<T>();
        exec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    future.complete(supplier.get());
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            }
        });
        return future;
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get();
    }
}
