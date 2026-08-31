package com.team4u.framework.flow;

import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import com.team4u.framework.flow.api.FlowObserver;
import com.team4u.framework.flow.api.Metadata;
import com.team4u.framework.flow.api.ObserverSafeEmitter;
import com.team4u.framework.flow.api.ResumePoint;
import com.team4u.framework.flow.compiler.Compiler;
import com.team4u.framework.flow.engine.MachineResult;
import com.team4u.framework.flow.engine.MachineState;
import com.team4u.framework.flow.engine.SerialMachine;
import com.team4u.framework.flow.model.Cancellation;
import com.team4u.framework.flow.model.FlowResult;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Suspension;
import com.team4u.framework.flow.spi.NodeDescriptor;

/**
 * 逻辑 Flow 在 Local 内存模式下的编译后可执行句柄。
 *
 * <p>核心能力与设计特性：
 * <ul>
 *   <li><b>多模式执行驱动</b>：提供同步（{@link #run}）与异步（{@link #runAsync}）驱动入口；</li>
 *   <li><b>异步挂起与恢复</b>：提供同步（{@link #resume}）与异步（{@link #resumeAsync}）续接入口，通过私有 {@code identity} 严格校验 {@link Suspension} 归属与防重复消费；</li>
 *   <li><b>死锁防御机制</b>：自动校验工作线程池配置，当流程包含并行分支或超时控制时，强制防御非 ForkJoinPool 线程池饥饿导致的死锁；</li>
 *   <li><b>可重配置性</b>：通过 {@link #withExecutor(ExecutorService)} 派生指定新工作线程池的执行实例。</li>
 * </ul>
 * </p>
 *
 * @param <I> 流程输入参数类型
 * @param <O> 流程输出结果类型
 * @author jay.wu
 */
public final class LocalExecutable<I, O> {
    private final Compiler.Compiled compiled;
    private final FlowObserver observer;
    private final ExecutorService workerExecutor;
    /** 流程标识与版本，参与 invocationId 与事件 Metadata 组成。 */
    private final String flowId;
    private final int flowVersion;
    /** 用于校验 Suspension 归属的私有标识。 */
    private final Object identity = new Object();

    LocalExecutable(Compiler.Compiled compiled, FlowObserver observer, ExecutorService workerExecutor) {
        this(compiled, "local", 0, observer, workerExecutor);
    }

    /**
     * 内部工厂：指定流程标识与版本构造执行器。
     *
     * @param compiled      编译产物
     * @param flowId        流程标识
     * @param flowVersion   流程版本
     * @param observer      事件观察者
     * @param workerExecutor 工作线程池
     * @param <I>           输入类型
     * @param <O>           输出类型
     * @return 执行器实例
     */
    static <I, O> LocalExecutable<I, O> create(Compiler.Compiled compiled,
                                                String flowId, int flowVersion,
                                                FlowObserver observer,
                                                ExecutorService workerExecutor) {
        return new LocalExecutable<I, O>(compiled, flowId, flowVersion, observer, workerExecutor);
    }

    private LocalExecutable(Compiler.Compiled compiled, String flowId, int flowVersion,
                            FlowObserver observer, ExecutorService workerExecutor) {
        this.compiled = Objects.requireNonNull(compiled, "compiled must not be null");
        this.observer = Objects.requireNonNull(observer, "observer must not be null");
        this.workerExecutor = Objects.requireNonNull(workerExecutor, "workerExecutor must not be null");
        this.flowId = Objects.requireNonNull(flowId, "flowId must not be null");
        if (flowId.trim().isEmpty()) {
            throw new IllegalArgumentException("flowId must not be blank");
        }
        this.flowVersion = flowVersion;
        validateWorkerExecutor(compiled, this.workerExecutor);
    }

    /**
     * 派生指定工作线程池的新 LocalExecutable 实例。
     *
     * @param workerExecutor 新的工作线程池（为 null 时使用 commonPool）
     * @return 重新绑定线程池的 {@link LocalExecutable}
     */
    public LocalExecutable<I, O> withExecutor(ExecutorService workerExecutor) {
        return new LocalExecutable<I, O>(compiled, flowId, flowVersion, observer,
                workerExecutor != null ? workerExecutor : ForkJoinPool.commonPool());
    }

    /**
     * 同步启动流程执行（使用默认新创建的取消令牌）。
     *
     * @param input 流程输入数据，不能为 null
     * @return 执行结果 {@link FlowResult}（Completed / Suspended / Cancelled）
     * @throws NullPointerException 当 {@code input} 为 null 时抛出
     */
    public FlowResult<O> run(I input) {
        return run(input, Cancellation.create());
    }

    /**
     * 同步启动流程执行（传入指定的取消令牌）。
     *
     * @param input        流程输入数据，不能为 null
     * @param cancellation 外部取消令牌，不能为 null
     * @return 执行结果 {@link FlowResult}
     * @throws NullPointerException 当参数为 null 时抛出
     */
    public FlowResult<O> run(I input, Cancellation cancellation) {
        Objects.requireNonNull(input, "input must not be null");
        cancellation = Objects.requireNonNull(cancellation, "cancellation must not be null");
        String executionId = UUID.randomUUID().toString();
        MachineState state = new MachineState(compiled.root(), executionId, input);
        event(FlowObserver.Type.FLOW_STARTED, executionId,
                compiled.root().descriptor(), Collections.emptyMap());
        return drive(state, cancellation, executionId);
    }

    /**
     * 异步启动流程执行（使用默认 commonPool 调度）。
     *
     * <p><b>线程池建议</b>：默认借用 {@link ForkJoinPool#commonPool()} 调度顶层驱动。
     * commonPool 与 JVM 内所有其他使用者共享，若流程含阻塞性 Operation 或高并发入口，
     * 建议通过 {@link #runAsync(Object, ExecutorService)} 显式传入独立的 dispatcher 线程池，
     * 避免与并行分支 Worker 相互干扰。</p>
     *
     * @param input 流程输入数据，不能为 null
     * @return 异步执行结果阶段 {@link CompletionStage}
     */
    public CompletionStage<FlowResult<O>> runAsync(I input) {
        return runAsync(input, Cancellation.create(), ForkJoinPool.commonPool());
    }

    /**
     * 异步启动流程执行（传入取消令牌，使用 commonPool 调度）。
     *
     * @param input        流程输入数据，不能为 null
     * @param cancellation 取消令牌，不能为 null
     * @return 异步执行结果阶段 {@link CompletionStage}
     */
    public CompletionStage<FlowResult<O>> runAsync(I input, Cancellation cancellation) {
        return runAsync(input, cancellation, ForkJoinPool.commonPool());
    }

    /**
     * 异步启动流程执行（传入指定的调度线程池）。
     *
     * @param input      流程输入数据，不能为 null
     * @param dispatcher 外部调度线程池
     * @return 异步执行结果阶段 {@link CompletionStage}
     */
    public CompletionStage<FlowResult<O>> runAsync(I input, ExecutorService dispatcher) {
        return runAsync(input, Cancellation.create(), dispatcher);
    }

    /**
     * 异步启动流程执行（全参重载）。
     *
     * @param input        流程输入数据，不能为 null
     * @param cancellation 取消令牌，不能为 null
     * @param dispatcher   外部调度线程池（为 null 时使用 commonPool）
     * @return 异步执行结果阶段 {@link CompletionStage}
     */
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

    /**
     * 同步恢复挂起的流程执行。
     *
     * @param suspension 挂起句柄，不能为 null
     * @param point      挂起点标识，不能为 null
     * @param signal     恢复信号数据，不能为 null
     * @param <R>        恢复信号数据类型
     * @return 执行推进后的结果 {@link FlowResult}
     */
    public <R> FlowResult<O> resume(Suspension<O> suspension,
                                    ResumePoint<R> point, R signal) {
        return resume(suspension, point, signal, Cancellation.create());
    }

    /**
     * 同步恢复挂起的流程执行（传入取消令牌）。
     *
     * @param suspension   挂起句柄，不能为 null
     * @param point        挂起点标识，不能为 null
     * @param signal       恢复信号数据，不能为 null
     * @param cancellation 取消令牌，不能为 null
     * @param <R>          恢复信号数据类型
     * @return 执行推进后的结果 {@link FlowResult}
     * @throws IllegalArgumentException 当句柄不属于当前执行器或挂起点名称不匹配时抛出
     * @throws IllegalStateException    当句柄已被消费或内部状态非挂起态时抛出
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
        MachineState state = MachineState.validateResume(suspension.engineState(), point.name());
        if (!suspension.consume()) {
            throw new IllegalStateException("Suspension was already consumed");
        }
        state.beginResume(signal);
        return drive(state, cancellation, suspension.executionId());
    }

    /**
     * 异步恢复挂起的流程执行（使用 commonPool 调度）。
     *
     * @param suspension 挂起句柄，不能为 null
     * @param point      挂起点标识，不能为 null
     * @param signal     恢复信号数据，不能为 null
     * @param <R>        恢复信号数据类型
     * @return 异步推进结果阶段 {@link CompletionStage}
     */
    public <R> CompletionStage<FlowResult<O>> resumeAsync(
            Suspension<O> suspension, ResumePoint<R> point, R signal) {
        return resumeAsync(suspension, point, signal, Cancellation.create(), ForkJoinPool.commonPool());
    }

    /**
     * 异步恢复挂起的流程执行（传入取消令牌）。
     *
     * @param suspension   挂起句柄，不能为 null
     * @param point        挂起点标识，不能为 null
     * @param signal       恢复信号数据，不能为 null
     * @param cancellation 取消令牌，不能为 null
     * @param <R>          恢复信号数据类型
     * @return 异步推进结果阶段 {@link CompletionStage}
     */
    public <R> CompletionStage<FlowResult<O>> resumeAsync(
            Suspension<O> suspension, ResumePoint<R> point, R signal,
            Cancellation cancellation) {
        return resumeAsync(suspension, point, signal, cancellation, ForkJoinPool.commonPool());
    }

    /**
     * 异步恢复挂起的流程执行（传入调度线程池）。
     *
     * @param suspension 挂起句柄，不能为 null
     * @param point      挂起点标识，不能为 null
     * @param signal     恢复信号数据，不能为 null
     * @param dispatcher 调度线程池
     * @param <R>        恢复信号数据类型
     * @return 异步推进结果阶段 {@link CompletionStage}
     */
    public <R> CompletionStage<FlowResult<O>> resumeAsync(
            Suspension<O> suspension, ResumePoint<R> point, R signal,
            ExecutorService dispatcher) {
        return resumeAsync(suspension, point, signal, Cancellation.create(), dispatcher);
    }

    /**
     * 异步恢复挂起的流程执行（全参重载）。
     *
     * @param suspension   挂起句柄，不能为 null
     * @param point        挂起点标识，不能为 null
     * @param signal       恢复信号数据，不能为 null
     * @param cancellation 取消令牌，不能为 null
     * @param dispatcher   调度线程池（为 null 时使用 commonPool）
     * @param <R>          恢复信号数据类型
     * @return 异步推进结果阶段 {@link CompletionStage}
     */
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
     *
     * @param state       引擎内部状态机状态
     * @param cancellation 取消令牌
     * @param executionId 执行实例标识（用于事件与结果上报）
     */
    @SuppressWarnings("unchecked")
    private FlowResult<O> drive(MachineState state, Cancellation cancellation, String executionId) {
        SerialMachine machine = new SerialMachine(compiled.root(), flowId, flowVersion, state,
                cancellation, observer, workerExecutor);
        MachineResult result = machine.drive();
        switch (result.lifecycle()) {
            case COMPLETED:
                event(FlowObserver.Type.FLOW_COMPLETED, executionId,
                        compiled.root().descriptor(), Collections.singletonMap(
                                "outcome", result.outcome().kind().name()));
                return FlowResult.completed((Outcome<O>) result.outcome());
            case SUSPENDED:
                return FlowResult.suspended(new Suspension<O>(identity, state,
                        executionId, result.awaitingPoint()));
            case CANCELLED:
                event(FlowObserver.Type.FLOW_CANCELLED, executionId,
                        compiled.root().descriptor(), Collections.emptyMap());
                return FlowResult.cancelled(executionId);
            case ACTIVE:
            default:
                throw new IllegalStateException(
                        "Local execution cannot return an active wait");
        }
    }

    private void event(FlowObserver.Type type, String executionId,
                       NodeDescriptor descriptor, java.util.Map<String, String> attributes) {
        if (observer.isNoop()) return;
        ObserverSafeEmitter.emit(observer, new FlowObserver.Event(type, Instant.now(),
                new Metadata(flowId, flowVersion, executionId, descriptor.path(), descriptor.label()),
                descriptor, attributes));
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

