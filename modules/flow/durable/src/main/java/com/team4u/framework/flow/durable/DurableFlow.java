package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.Flow;

import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * 持久化流程实例，绑定特定版本并提供对外操作命令。
 *
 * @param <I> 流程入参类型
 * @param <O> 流程产物类型
 * @author jay.wu
 */
public class DurableFlow<I, O> {

    private final String flowId;
    private final int flowVersion;
    private final Flow<I, O> flowDefinition;
    private final DurableStore store;
    private final StateMapper stateMapper;
    private final DurableRunner runner;
    private final DurablePlanNode plan;

    DurableFlow(String flowId, int flowVersion, Flow<I, O> flowDefinition, DurableStore store, StateMapper stateMapper) {
        if (flowId == null || flowId.trim().isEmpty()) {
            throw new IllegalArgumentException("flowId must not be null or blank");
        }
        this.flowId = flowId;
        if (flowVersion <= 0) {
            throw new IllegalArgumentException("flowVersion must be a positive integer, got: " + flowVersion);
        }
        this.flowVersion = flowVersion;
        this.flowDefinition = Objects.requireNonNull(flowDefinition, "flowDefinition must not be null");
        this.store = Objects.requireNonNull(store, "DurableStore must not be null");
        this.stateMapper = Objects.requireNonNull(stateMapper, "StateMapper must not be null");
        this.runner = new DurableRunner(store, stateMapper);
        this.plan = flowDefinition.project(DurablePlanCompiler.INSTANCE);
    }

    public String flowId() {
        return flowId;
    }

    public int flowVersion() {
        return flowVersion;
    }

    public Flow<I, O> definition() {
        return flowDefinition;
    }

    /**
     * 开启新流程执行：落初始快照后开始执行。
     *
     * @param executionId 执行唯一标识，非 blank
     * @param input       流程入参，非 null
     * @return 执行结果
     */
    public DurableResult<O> start(String executionId, I input) {
        if (executionId == null || executionId.trim().isEmpty()) {
            throw new IllegalArgumentException("executionId must not be null or blank");
        }
        Objects.requireNonNull(input, "input must not be null");

        DurableSnapshot existing = store.load(flowId, executionId);
        if (existing != null) {
            throw new IllegalStateException("Execution [" + executionId + "] already exists in flow [" + flowId + "]");
        }

        StoredValue inputSlot;
        try {
            inputSlot = stateMapper.encode(input);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to encode input for execution [" + executionId + "]", e);
        }

        DurableSnapshot initial = DurableSnapshot.initial(flowId, flowVersion, executionId, inputSlot);
        boolean saved = store.save(initial, 0L);
        if (!saved) {
            throw new IllegalStateException("CAS conflict on creating execution [" + executionId + "]");
        }

        return runner.run(plan, initial);
    }

    /**
     * 恢复执行处于 ACTIVE 状态的流程（例如进程崩溃重启后）。
     *
     * @param executionId 执行唯一标识，非 blank
     * @return 执行结果
     */
    public DurableResult<O> recover(String executionId) {
        if (executionId == null || executionId.trim().isEmpty()) {
            throw new IllegalArgumentException("executionId must not be null or blank");
        }
        DurableSnapshot snapshot = store.load(flowId, executionId);
        if (snapshot == null) {
            throw new NoSuchElementException("Execution [" + executionId + "] not found in flow [" + flowId + "]");
        }
        validateSnapshot(snapshot, executionId);
        if (snapshot.lifecycle() != DurableLifecycle.ACTIVE) {
            return toResult(snapshot);
        }
        return runner.run(plan, snapshot);
    }

    /**
     * 重试处于 FAILED 状态的执行：CAS 转回 ACTIVE 并从最后成功快照重放失败节点。
     *
     * @param executionId 执行唯一标识，非 blank
     * @return 执行结果
     */
    public DurableResult<O> retry(String executionId) {
        if (executionId == null || executionId.trim().isEmpty()) {
            throw new IllegalArgumentException("executionId must not be null or blank");
        }
        DurableSnapshot snapshot = store.load(flowId, executionId);
        if (snapshot == null) {
            throw new NoSuchElementException("Execution [" + executionId + "] not found in flow [" + flowId + "]");
        }
        validateSnapshot(snapshot, executionId);
        if (snapshot.lifecycle() != DurableLifecycle.FAILED) {
            throw new IllegalStateException("Cannot retry execution [" + executionId +
                    "] with lifecycle [" + snapshot.lifecycle() + "] (expected FAILED)");
        }

        DurableSnapshot activeSnap = snapshot.withRetryActive();
        boolean saved = store.save(activeSnap, snapshot.revision());
        if (!saved) {
            throw new IllegalStateException("CAS conflict when retrying execution [" + executionId + "]");
        }

        return runner.run(plan, activeSnap);
    }

    /**
     * 取消流程执行（支持 ACTIVE 或 FAILED 状态）。
     *
     * @param executionId 执行唯一标识，非 blank
     * @return true 表示成功取消，false 表示已处于完成/停止状态或未找到
     */
    public boolean cancel(String executionId) {
        if (executionId == null || executionId.trim().isEmpty()) {
            throw new IllegalArgumentException("executionId must not be null or blank");
        }
        DurableSnapshot snapshot = store.load(flowId, executionId);
        if (snapshot == null) {
            return false;
        }
        validateSnapshot(snapshot, executionId);
        if (snapshot.lifecycle() == DurableLifecycle.COMPLETED ||
                snapshot.lifecycle() == DurableLifecycle.STOPPED ||
                snapshot.lifecycle() == DurableLifecycle.CANCELLED) {
            return false;
        }

        DurableSnapshot cancelSnap = snapshot.withCancelled();
        return store.save(cancelSnap, snapshot.revision());
    }

    /**
     * 查询当前快照执行结果（纯读与解码，不执行 ACTIVE 业务节点）。
     *
     * @param executionId 执行唯一标识，非 blank
     * @return 执行结果
     */
    public DurableResult<O> load(String executionId) {
        if (executionId == null || executionId.trim().isEmpty()) {
            throw new IllegalArgumentException("executionId must not be null or blank");
        }
        DurableSnapshot snapshot = store.load(flowId, executionId);
        if (snapshot == null) {
            throw new NoSuchElementException("Execution [" + executionId + "] not found in flow [" + flowId + "]");
        }
        validateSnapshot(snapshot, executionId);
        return toResult(snapshot);
    }

    private void validateSnapshot(DurableSnapshot snapshot, String expectedExecutionId) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        if (!Objects.equals(snapshot.flowId(), this.flowId)) {
            throw new IllegalStateException("Snapshot flowId [" + snapshot.flowId() +
                    "] does not match registered flowId [" + this.flowId + "]");
        }

        if (snapshot.flowVersion() != this.flowVersion) {
            throw new IllegalStateException("Snapshot version [" + snapshot.flowVersion() +
                    "] does not match registered flow version [" + this.flowVersion + "]");
        }

        if (!Objects.equals(snapshot.executionId(), expectedExecutionId)) {
            throw new IllegalStateException("Snapshot executionId [" + snapshot.executionId() +
                    "] does not match requested executionId [" + expectedExecutionId + "]");
        }

        if (!DurableSnapshot.DEFAULT_FORMAT_ID.equals(snapshot.formatId())) {
            throw new IllegalStateException("Unsupported snapshot formatId [" + snapshot.formatId() +
                    "], expected [" + DurableSnapshot.DEFAULT_FORMAT_ID + "]");
        }

        if (snapshot.formatVersion() != DurableSnapshot.DEFAULT_FORMAT_VERSION) {
            throw new IllegalStateException("Unsupported snapshot formatVersion [" + snapshot.formatVersion() +
                    "], expected [" + DurableSnapshot.DEFAULT_FORMAT_VERSION + "]");
        }

        if (snapshot.revision() < 0) {
            throw new IllegalStateException("Snapshot revision must be non-negative, got: " + snapshot.revision());
        }

        if (snapshot.lifecycle() == null) {
            throw new IllegalStateException("Snapshot lifecycle must not be null");
        }

        if (snapshot.frameState() == null) {
            throw new IllegalStateException("Snapshot frameState must not be null");
        }

        if (snapshot.slots() == null) {
            throw new IllegalStateException("Snapshot slots must not be null");
        }

        switch (snapshot.lifecycle()) {
            case ACTIVE:
                if (snapshot.stopReason() != null) {
                    throw new IllegalStateException("ACTIVE snapshot must not contain stopReason");
                }
                if (snapshot.failure() != null) {
                    throw new IllegalStateException("ACTIVE snapshot must not contain failure");
                }
                break;
            case COMPLETED:
                if (snapshot.stopReason() != null) {
                    throw new IllegalStateException("COMPLETED snapshot must not contain stopReason");
                }
                if (snapshot.failure() != null) {
                    throw new IllegalStateException("COMPLETED snapshot must not contain failure");
                }
                break;
            case STOPPED:
                if (snapshot.stopReason() == null) {
                    throw new IllegalStateException("STOPPED snapshot must contain stopReason");
                }
                if (snapshot.failure() != null) {
                    throw new IllegalStateException("STOPPED snapshot must not contain failure");
                }
                break;
            case FAILED:
                if (snapshot.failure() == null) {
                    throw new IllegalStateException("FAILED snapshot must contain failure");
                }
                if (snapshot.stopReason() != null) {
                    throw new IllegalStateException("FAILED snapshot must not contain stopReason");
                }
                break;
            case CANCELLED:
                break;
            default:
                throw new IllegalStateException("Unknown lifecycle: " + snapshot.lifecycle());
        }
    }

    @SuppressWarnings("unchecked")
    private DurableResult<O> toResult(DurableSnapshot snapshot) {
        switch (snapshot.lifecycle()) {
            case COMPLETED:
                try {
                    StoredValue outSlot = snapshot.getSlot("output");
                    if (outSlot == null) {
                        outSlot = snapshot.getSlot("active");
                    }
                    O val = outSlot != null ? (O) stateMapper.decode(outSlot) : null;
                    return DurableResult.completed(snapshot.flowId(), snapshot.flowVersion(), snapshot.executionId(), snapshot.revision(), val);
                } catch (Exception e) {
                    DurableFailure f = new DurableFailure("codec", "codec", e.getClass().getName(), e.getMessage());
                    return DurableResult.failed(snapshot.flowId(), snapshot.flowVersion(), snapshot.executionId(), snapshot.revision(), f);
                }
            case STOPPED:
                return DurableResult.stopped(snapshot.flowId(), snapshot.flowVersion(), snapshot.executionId(), snapshot.revision(), snapshot.stopReason());
            case FAILED:
                return DurableResult.failed(snapshot.flowId(), snapshot.flowVersion(), snapshot.executionId(), snapshot.revision(), snapshot.failure());
            case CANCELLED:
                return DurableResult.cancelled(snapshot.flowId(), snapshot.flowVersion(), snapshot.executionId(), snapshot.revision());
            case ACTIVE:
            default:
                return DurableResult.active(snapshot.flowId(), snapshot.flowVersion(), snapshot.executionId(), snapshot.revision());
        }
    }
}
