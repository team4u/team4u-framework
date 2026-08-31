package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.FlowObserver;
import com.team4u.framework.flow.Outcome;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;

/**
 * 绑定特定 flowId 与 flowVersion 的可持久化流程执行句柄（Durable Executable Handle）。
 *
 * <p>提供受 CAS 乐观锁版本保护的崩溃恢复、启动、挂起信号注入与状态查询能力。所有写操作遵循统一的状态机事务范式：
 * <pre>{@code
 *   Load Snapshot (无副作用读取) -> 校验当前生命周期 -> 计算状态转移变更 -> CAS 提交更新 -> 驱动内存状态机执行
 * }</pre>
 * </p>
 *
 * @param <I> 流程输入类型
 * @param <O> 流程输出类型
 * @author team4u
 */
public final class DurableExecutable<I, O> {
    private final String flowId;
    private final int flowVersion;
    private final DurablePlanCompiler.Definition definition;
    private final DurableStore store;
    private final StateMapper stateMapper;
    private final FlowObserver observer;
    private final DurableObserver durableObserver;
    private final ExecutorService executor;

    DurableExecutable(String flowId, int flowVersion,
                      DurablePlanCompiler.Definition definition,
                      DurableStore store, StateMapper stateMapper,
                      FlowObserver observer, DurableObserver durableObserver,
                      ExecutorService executor) {
        this.flowId = text(flowId, "flowId");
        if (flowVersion < 1) {
            throw new IllegalArgumentException("flowVersion must be positive");
        }
        this.flowVersion = flowVersion;
        this.definition = Objects.requireNonNull(definition, "definition must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.stateMapper = Objects.requireNonNull(stateMapper, "stateMapper must not be null");
        this.observer = Objects.requireNonNull(observer, "observer must not be null");
        this.durableObserver = Objects.requireNonNull(
                durableObserver, "durableObserver must not be null");
        this.executor = executor;
    }

    /**
     * 获取绑定的流程唯一标识。
     *
     * @return 流程标识
     */
    public String flowId() {
        return flowId;
    }

    /**
     * 获取绑定的流程版本号。
     *
     * @return 版本号
     */
    public int flowVersion() {
        return flowVersion;
    }

    // ------------------------------------------------------------------
    // start
    // ------------------------------------------------------------------

    /**
     * 开启并驱动全新流程实例执行。
     *
     * <p>首先向持久化存储以 CAS 提交版本号为 1 的初始活跃快照（ACTIVE），若已存在同名 executionId 则抛出 {@link DurableException.Error#EXECUTION_EXISTS}；
     * 随后启动状态机驱动执行直至完成、挂起或需要外部退避唤醒。</p>
     *
     * @param executionId 流程执行实例全局唯一 ID，不能为空
     * @param input       流程初始输入载荷，不能为 null
     * @return 持久化执行结果 {@link DurableResult}
     * @throws DurableException 当 executionId 已存在或底层存储失败时抛出
     */
    public DurableResult<O> start(String executionId, I input) {

        text(executionId, "executionId");
        Objects.requireNonNull(input, "input must not be null");
        if (store.load(executionId).isPresent()) {
            throw new DurableException(DurableException.Error.EXECUTION_EXISTS,
                    "Execution already started: " + executionId);
        }
        DurableState.MachineState state = new DurableState.MachineState(
                definition.root(), executionId, input,
                DurableState.SlotRole.user("input"));
        Checkpoints checkpoints = Checkpoints.create(store, executionId, flowId, flowVersion,
                stateMapper, durableObserver, definition, state, -1L);
        checkpoints.commit(CheckpointReasons.initial());
        return drive(state, checkpoints);
    }

    // ------------------------------------------------------------------
    // resume
    // ------------------------------------------------------------------

    /**
     * 向处于挂起状态（{@link DurableLifecycle#SUSPENDED}）的流程注入恢复信号并续跑执行。
     *
     * <p>执行流程：
     * <ul>
     *   <li>读取快照并校验处于 SUSPENDED 状态且目标挂起点（pointName）完全匹配；</li>
     *   <li>将 signal 编码为持久化 StoredValue 写入快照插槽，并通过 CAS 提交为 ACTIVE 状态；</li>
     *   <li>若 CAS 成功，驱动内存状态机消费该恢复信号并继续推进后续编排流程。</li>
     * </ul>
     * </p>
     *
     * @param executionId 流程执行实例 ID，不能为空
     * @param pointName   目标挂起点名称，不能为空
     * @param signal      外部注入的恢复信号载荷，不能为 null
     * @param <R>         恢复信号泛型类型
     * @return 执行推进结果
     * @throws DurableException 若状态不为 SUSPENDED、挂起点不匹配或信号发生冲突时抛出
     */
    public <R> DurableResult<O> resume(String executionId, String pointName, R signal) {
        return resume(executionId, pointName, signal, true);
    }

    @SuppressWarnings("unchecked")
    <R> DurableResult<O> resume(String executionId, String pointName, R signal,
                                boolean requirePendingAbsent) {
        text(executionId, "executionId");
        text(pointName, "resume point");
        Objects.requireNonNull(signal, "signal must not be null");
        Loaded loaded = load(executionId);
        DurableSnapshot snapshot = loaded.snapshot;
        switch (snapshot.lifecycle()) {
            case COMPLETED:
            case CANCELLED:
                throw lifecycle("Cannot resume " + snapshot.lifecycle() + " execution");
            case ACTIVE:
                if (!snapshot.pendingResume()) {
                    throw lifecycle("Cannot resume an ACTIVE execution without a pending signal");
                }
                break;
            case SUSPENDED:
            default:
                break;
        }
        if (!pointName.equals(snapshot.awaitingPoint())) {
            throw new DurableException(DurableException.Error.RESUME_POINT_MISMATCH,
                    "Execution is awaiting [" + snapshot.awaitingPoint()
                            + "], not [" + pointName + "]");
        }
        ResumeDecoder decoded = decodeSnapshot(snapshot);
        DurableState.MachineState state = decoded.state;
        Checkpoints checkpoints = decoded.checkpoints;
        if (snapshot.pendingResume()) {
            // 信号已落库（崩溃在落库后、消费前）：不同值冲突，同值幂等重驱动
            StoredValue persistedSlot = snapshot.slots().get(
                    DurablePlanCompiler.resumeRole(pointName));
            if (persistedSlot != null && !sameSignal(persistedSlot, signal)) {
                throw new DurableException(DurableException.Error.RESUME_SIGNAL_CONFLICT,
                        "Resume point [" + pointName + "] already has a different signal");
            }
            return driveContinuation(state, checkpoints);
        }
        // 第一步：把信号写入 resume:<name> 槽，CAS 为 ACTIVE+pendingResume（独立提交）
        Map<String, StoredValue> slots = new LinkedHashMap<String, StoredValue>(
                snapshot.slots());
        StoredValue encoded;
        try {
            encoded = SnapshotCodec.encodeUser(stateMapper,
                    DurablePlanCompiler.resumeRole(pointName), signal);
        } catch (DurableException error) {
            throw error;
        }
        slots.put(DurablePlanCompiler.resumeRole(pointName), encoded);
        DurableSnapshot signaled = new DurableSnapshot(executionId, flowId, flowVersion,
                DurableSnapshot.CURRENT_FORMAT_ID, DurableSnapshot.CURRENT_FORMAT_VERSION,
                snapshot.revision() + 1, DurableLifecycle.ACTIVE,
                snapshot.frameMetadata(), slots, pointName, true);
        try {
            if (!store.compareAndSet(executionId, snapshot.revision(), signaled)) {
                throw Checkpoints.conflict(executionId);
            }
        } catch (DurableException error) {
            throw error;
        } catch (Exception error) {
            throw Checkpoints.storeFailure(error);
        }
        durableSignalEvent(signaled, pointName);
        // 重新加载并解码：pendingSignal 由信封重建，与崩溃后恢复路径完全一致
        Loaded reloaded = load(executionId);
        ResumeDecoder resumeState = decodeSnapshot(reloaded.snapshot);
        return driveContinuation(resumeState.state, resumeState.checkpoints);
    }

    /** 挂起后续接：激活 ACTIVE 后驱动机器消费 pendingSignal 并推进。 */
    private DurableResult<O> driveContinuation(DurableState.MachineState state,
                                               Checkpoints checkpoints) {
        state.lifecycle = DurableLifecycle.ACTIVE;
        return drive(state, checkpoints);
    }

    private boolean sameSignal(StoredValue persisted, Object signal) {
        try {
            StoredValue encoded = SnapshotCodec.encodeUser(stateMapper,
                    DurablePlanCompiler.resumeRole("compare"), signal);
            return persisted.equals(encoded);
        } catch (Exception error) {
            return false;
        }
    }
    private void durableSignalEvent(DurableSnapshot snapshot, String pointName) {
        try {
            Map<String, String> attributes = new LinkedHashMap<String, String>();
            attributes.put("resumePoint", pointName);
            durableObserver.onEvent(new DurableObserver.Event(
                    DurableObserver.Type.RESUME_SIGNAL_PERSISTED, Instant.now(),
                    new com.team4u.framework.flow.Metadata(flowId, flowVersion,
                            snapshot.executionId(), "$",
                            java.util.Optional.<String>empty()),
                    snapshot.revision(), snapshot.lifecycle(), attributes));
        } catch (RuntimeException ignored) {
            // Durable observers cannot alter execution.
        }
    }

    // ------------------------------------------------------------------
    // recover
    // ------------------------------------------------------------------

    /**
     * 从崩溃或重启中恢复处于活跃状态（{@link DurableLifecycle#ACTIVE}）的流程执行。
     *
     * <p>从底层存储读取最新检查点快照，重建执行帧栈状态机，并从最近一次成功提交的检查点之后继续驱动执行。</p>
     *
     * @param executionId 流程执行实例 ID，不能为空
     * @return 恢复执行结果
     * @throws DurableException 若流程不存在或其生命周期并非 ACTIVE 时抛出
     */
    @SuppressWarnings("unchecked")
    public DurableResult<O> recover(String executionId) {
        text(executionId, "executionId");
        Loaded loaded = load(executionId);
        DurableSnapshot snapshot = loaded.snapshot;
        if (snapshot.lifecycle() != DurableLifecycle.ACTIVE) {
            throw lifecycle("recover requires an ACTIVE execution, but was "
                    + snapshot.lifecycle());
        }
        ResumeDecoder decoded = decodeSnapshot(snapshot);
        durableRestoredEvent(snapshot);
        return drive(decoded.state, decoded.checkpoints);
    }

    // ------------------------------------------------------------------
    // cancel
    // ------------------------------------------------------------------

    /**
     * 取消处于活跃（ACTIVE）或挂起（SUSPENDED）状态的流程执行。
     *
     * <p>原子将快照状态 CAS 置为 {@link DurableLifecycle#CANCELLED} 终态；若已处于 COMPLETED 或 CANCELLED 终态则抛出生命周期异常。</p>
     *
     * @param executionId 流程执行实例 ID，不能为空
     * @return 取消终态结果
     * @throws DurableException 若流程已处于终态或 CAS 冲突时抛出
     */
    public DurableResult<O> cancel(String executionId) {
        text(executionId, "executionId");
        Loaded loaded = load(executionId);
        DurableSnapshot snapshot = loaded.snapshot;
        if (snapshot.lifecycle() == DurableLifecycle.COMPLETED
                || snapshot.lifecycle() == DurableLifecycle.CANCELLED) {
            throw lifecycle("Cannot cancel " + snapshot.lifecycle() + " execution");
        }
        DurableSnapshot cancelled = new DurableSnapshot(executionId, flowId, flowVersion,
                DurableSnapshot.CURRENT_FORMAT_ID, DurableSnapshot.CURRENT_FORMAT_VERSION,
                snapshot.revision() + 1, DurableLifecycle.CANCELLED,
                snapshot.frameMetadata(), snapshot.slots(), null, false);
        try {
            if (!store.compareAndSet(executionId, snapshot.revision(), cancelled)) {
                throw Checkpoints.conflict(executionId);
            }
        } catch (DurableException error) {
            throw error;
        } catch (Exception error) {
            throw Checkpoints.storeFailure(error);
        }
        checkpointEvent(cancelled, CheckpointReasons.cancelled());
        return new DurableResult.Cancelled<O>(cancelled);
    }

    // ------------------------------------------------------------------
    // load（无副作用）
    // ------------------------------------------------------------------

    /**
     * 只读加载流程实例的最新快照（无副作用）。
     *
     * @param executionId 流程执行实例 ID，不能为空
     * @return 若存在返回快照 Optional，否则返回 empty
     */
    public Optional<DurableSnapshot> snapshot(String executionId) {
        text(executionId, "executionId");
        try {
            return store.load(executionId);
        } catch (DurableException error) {
            throw error;
        } catch (Exception error) {
            throw Checkpoints.storeFailure(error);
        }
    }

    // ------------------------------------------------------------------
    // async
    // ------------------------------------------------------------------

    /**
     * 异步启动流程执行。
     *
     * @param executionId 流程执行实例 ID，不能为空
     * @param input       初始输入载荷，不能为 null
     * @return 异步执行阶段 Future
     * @throws DurableException 若未配置 executor 时抛出
     */
    public CompletionStage<DurableResult<O>> startAsync(final String executionId, final I input) {
        final ExecutorService async = requireExecutor();
        final CompletableFuture<DurableResult<O>> future = new CompletableFuture<DurableResult<O>>();
        async.execute(new Runnable() {
            @Override public void run() {
                try {
                    future.complete(start(executionId, input));
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                }
            }
        });
        return future;
    }

    /**
     * 异步恢复挂起流程执行。
     *
     * @param executionId 流程执行实例 ID，不能为空
     * @param pointName   目标挂起点名称，不能为空
     * @param signal      恢复信号载荷，不能为 null
     * @param <R>         恢复信号泛型
     * @return 异步执行阶段 Future
     * @throws DurableException 若未配置 executor 时抛出
     */
    public <R> CompletionStage<DurableResult<O>> resumeAsync(final String executionId,
                                                             final String pointName,
                                                             final R signal) {

        final ExecutorService async = requireExecutor();
        final CompletableFuture<DurableResult<O>> future = new CompletableFuture<DurableResult<O>>();
        async.execute(new Runnable() {
            @Override public void run() {
                try {
                    future.complete(resume(executionId, pointName, signal));
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                }
            }
        });
        return future;
    }

    private ExecutorService requireExecutor() {
        if (executor == null) {
            throw new DurableException(DurableException.Error.ASYNC_EXECUTOR_MISSING,
                    "Async commands require a caller-owned executor");
        }
        return executor;
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private static final class Loaded {
        final DurableSnapshot snapshot;

        Loaded(DurableSnapshot snapshot) {
            this.snapshot = snapshot;
        }
    }

    private static final class ResumeDecoder {
        final DurableState.MachineState state;
        final Checkpoints checkpoints;

        ResumeDecoder(DurableState.MachineState state, Checkpoints checkpoints) {
            this.state = state;
            this.checkpoints = checkpoints;
        }
    }

    private Loaded load(String executionId) {
        Optional<DurableSnapshot> found;
        try {
            found = store.load(executionId);
        } catch (DurableException error) {
            throw error;
        } catch (Exception error) {
            throw Checkpoints.storeFailure(error);
        }
        if (!found.isPresent()) {
            throw new DurableException(DurableException.Error.EXECUTION_NOT_FOUND,
                    "Execution not found: " + executionId);
        }
        DurableSnapshot snapshot = found.get();
        if (!flowId.equals(snapshot.flowId()) || flowVersion != snapshot.flowVersion()) {
            throw new DurableException(DurableException.Error.FLOW_MISMATCH,
                    "Snapshot belongs to flow " + snapshot.flowId() + ":"
                            + snapshot.flowVersion());
        }
        if (!DurableSnapshot.CURRENT_FORMAT_ID.equals(snapshot.formatId())
                || DurableSnapshot.CURRENT_FORMAT_VERSION != snapshot.formatVersion()) {
            throw new DurableException(DurableException.Error.FORMAT_MISMATCH,
                    "Unsupported snapshot format " + snapshot.formatId() + ":"
                            + snapshot.formatVersion());
        }
        return new Loaded(snapshot);
    }

    private ResumeDecoder decodeSnapshot(DurableSnapshot snapshot) {
        DurableState.MachineState state;
        try {
            state = SnapshotCodec.decode(snapshot, stateMapper, definition);
        } catch (DurableException error) {
            throw error;
        }
        if (snapshot.lifecycle() == DurableLifecycle.SUSPENDED) {
            state.lifecycle = DurableLifecycle.SUSPENDED;
        } else {
            state.lifecycle = DurableLifecycle.ACTIVE;
        }
        Checkpoints checkpoints = Checkpoints.create(store, snapshot.executionId(),
                flowId, flowVersion, stateMapper, durableObserver, definition,
                state, snapshot.revision());
        return new ResumeDecoder(state, checkpoints);
    }

    @SuppressWarnings("unchecked")
    private DurableResult<O> drive(DurableState.MachineState state, Checkpoints checkpoints) {
        DurableMachine machine = new DurableMachine(definition, flowId, flowVersion,
                state, checkpoints, observer, executor, false);
        DurableState.MachineResult result = machine.drive();
        DurableSnapshot finalSnapshot = latestSnapshot(checkpoints);
        switch (result.lifecycle()) {
            case COMPLETED:
                return new DurableResult.Completed<O>(
                        (Outcome<O>) result.outcome().outcome(), finalSnapshot);
            case SUSPENDED:
                return new DurableResult.Suspended<O>(result.awaitingPoint(), finalSnapshot);
            case CANCELLED:
                return new DurableResult.Cancelled<O>(finalSnapshot);
            case ACTIVE:
            default:
                // 退避等待（RETRY/PersistentPolicy）：快照已落 ACTIVE+wake，返回唤醒时机
                return new DurableResult.Active<O>(
                        Optional.ofNullable(result.wakeAt()), finalSnapshot);
        }
    }

    private DurableSnapshot latestSnapshot(Checkpoints checkpoints) {
        Optional<DurableSnapshot> found;
        try {
            found = store.load(checkpoints.executionId());
        } catch (DurableException error) {
            throw error;
        } catch (Exception error) {
            throw Checkpoints.storeFailure(error);
        }
        if (!found.isPresent()) {
            throw new DurableException(DurableException.Error.EXECUTION_NOT_FOUND,
                    "Execution disappeared: " + checkpoints.executionId());
        }
        return found.get();
    }

    private void durableRestoredEvent(DurableSnapshot snapshot) {
        try {
            Map<String, String> attributes = new LinkedHashMap<String, String>();
            attributes.put("revision", Long.toString(snapshot.revision()));
            durableObserver.onEvent(new DurableObserver.Event(
                    DurableObserver.Type.CHECKPOINT_RESTORED, Instant.now(),
                    new com.team4u.framework.flow.Metadata(flowId, flowVersion,
                            snapshot.executionId(), "$",
                            java.util.Optional.<String>empty()),
                    snapshot.revision(), snapshot.lifecycle(), attributes));
        } catch (RuntimeException ignored) {
            // Durable observers cannot alter execution.
        }
    }

    private void checkpointEvent(DurableSnapshot snapshot, CheckpointReasons.Reason reason) {
        try {
            Map<String, String> attributes = new LinkedHashMap<String, String>();
            attributes.put("kind", reason.kind());
            attributes.put("path", reason.path());
            durableObserver.onEvent(new DurableObserver.Event(
                    DurableObserver.Type.CHECKPOINT_COMMITTED, Instant.now(),
                    new com.team4u.framework.flow.Metadata(flowId, flowVersion,
                            snapshot.executionId(), reason.path(),
                            java.util.Optional.<String>empty()),
                    snapshot.revision(), snapshot.lifecycle(), attributes));
        } catch (RuntimeException ignored) {
            // Durable observers cannot alter execution.
        }
    }

    private static DurableException lifecycle(String message) {
        return new DurableException(DurableException.Error.LIFECYCLE_MISMATCH, message);
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
