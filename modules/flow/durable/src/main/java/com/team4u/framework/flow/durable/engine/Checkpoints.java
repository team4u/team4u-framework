package com.team4u.framework.flow.durable.engine;

import java.util.LinkedHashMap;
import java.util.Map;
import com.team4u.framework.flow.api.Metadata;
import com.team4u.framework.flow.durable.DurableException;
import com.team4u.framework.flow.durable.DurableLifecycle;
import com.team4u.framework.flow.durable.DurableObserver;
import com.team4u.framework.flow.durable.snapshot.DurableSnapshot;
import com.team4u.framework.flow.durable.snapshot.SnapshotCodec;
import com.team4u.framework.flow.durable.snapshot.StateMapper;
import com.team4u.framework.flow.durable.store.DurableStore;
import com.team4u.framework.flow.model.Reason;

/**
 * 检查点持久化协调器（Checkpoints Coordinator）。
 *
 * <p>负责在关键流程生命周期与步骤边界（如 Invoke 步进、Route 决策、Parallel 分支汇聚、Await 挂起、Complete 结束等）处，
 * 将内存中的 {@link DurableState.MachineState} 编码为紧凑快照（{@link DurableSnapshot}），
 * 并调用 {@link DurableStore#compareAndSet} 进行版本自增原子落库。若发生并发版本冲突则抛出 {@link DurableException.Error#REVISION_CONFLICT}。</p>
 *
 * @author jay.wu
 */
public final class Checkpoints {
    private static final class InertMarker {
        static final Checkpoints INSTANCE = new Checkpoints(null, null, null,
                0, null, null, null);
    }

    /** 分支机器使用的空提交器：分支内部不单独落检查点。 */
    public static final Checkpoints INERT = InertMarker.INSTANCE;


    private final DurableStore store;
    private final String executionId;
    private final String flowId;
    private final int flowVersion;
    private final StateMapper stateMapper;
    private final DurableObserver durableObserver;
    private final DurablePlanCompiler.Definition definition;

    private DurableState.MachineState state;
    private long revision;

    Checkpoints(DurableStore store, String executionId, String flowId, int flowVersion,
                StateMapper stateMapper, DurableObserver durableObserver,
                DurablePlanCompiler.Definition definition) {
        this.store = store;
        this.executionId = executionId;
        this.flowId = flowId;
        this.flowVersion = flowVersion;
        this.stateMapper = stateMapper;
        this.durableObserver = durableObserver;
        this.definition = definition;
    }

    public static Checkpoints create(DurableStore store, String executionId, String flowId,
                              int flowVersion, StateMapper stateMapper,
                              DurableObserver durableObserver,
                              DurablePlanCompiler.Definition definition,
                              DurableState.MachineState state, long baseRevision) {
        Checkpoints checkpoints = new Checkpoints(store, executionId, flowId, flowVersion,
                stateMapper, durableObserver, definition);
        checkpoints.state = state;
        checkpoints.revision = baseRevision;
        return checkpoints;
    }

    public String executionId() {
        return executionId;
    }

    public long revision() {
        return revision;
    }

    /** 把当前机器状态以 revision+1 CAS 提交。 */
    public void commit(CheckpointReasons.Reason reason) {
        if (store == null || state == null) {
            return;
        }
        DurableSnapshot snapshot = encode(state.lifecycle);
        boolean applied = false;
        try {
            applied = store.compareAndSet(executionId, revision, snapshot);
        } catch (DurableException error) {
            throw error;
        } catch (Exception error) {
            throw storeFailure(error);
        }
        if (!applied) {
            throw conflict(executionId);
        }
        revision++;
        durableEvent(DurableObserver.Type.CHECKPOINT_COMMITTED, snapshot, reason);
    }

    DurableSnapshot encode(DurableLifecycle lifecycle) {
        SnapshotCodec.Payload payload;
        try {
            payload = SnapshotCodec.encode(state, stateMapper, definition.slotRoles());
        } catch (DurableException error) {
            throw error;
        }
        return new DurableSnapshot(executionId, flowId, flowVersion,
                DurableSnapshot.CURRENT_FORMAT_ID, DurableSnapshot.CURRENT_FORMAT_VERSION,
                revision + 1, lifecycle, payload.metadata(), payload.slots(),
                state.awaitingPoint,
                state.pendingSignal != null);
    }

    private void durableEvent(DurableObserver.Type type, DurableSnapshot snapshot,
                              CheckpointReasons.Reason reason) {
        if (durableObserver == null) {
            return;
        }
        Map<String, String> attributes = new LinkedHashMap<String, String>();
        attributes.put("kind", reason.kind());
        attributes.put("path", reason.path());
        attributes.putAll(reason.attributes());
        try {
            durableObserver.onEvent(new DurableObserver.Event(type, java.time.Instant.now(),
                    new com.team4u.framework.flow.api.Metadata(flowId, flowVersion, executionId,
                            reason.path(), java.util.Optional.empty()),
                    snapshot.revision(), snapshot.lifecycle(), attributes));
        } catch (RuntimeException ignored) {
            // Durable observers cannot alter execution.
        }
    }

    public static DurableException conflict(String executionId) {
        return new DurableException(DurableException.Error.REVISION_CONFLICT,
                "Checkpoint revision conflict for execution " + executionId);
    }

    public static DurableException storeFailure(Exception cause) {
        return new DurableException(DurableException.Error.STORE_FAILURE,
                "Durable store operation failed: " + cause.getMessage(), cause);
    }
}
