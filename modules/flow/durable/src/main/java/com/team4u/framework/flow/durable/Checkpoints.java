package com.team4u.framework.flow.durable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 检查点提交协调器：把机器的内存状态编码为快照并按 revision CAS 提交。
 * 提交失败（revision 冲突）立即以 REVISION_CONFLICT 终止推进。
 */
final class Checkpoints {
    private static final class InertMarker {
        static final Checkpoints INSTANCE = new Checkpoints(null, null, null,
                0, null, null, null);
    }

    /** 分支机器使用的空提交器：分支内不落检查点。 */
    static final Checkpoints INERT = InertMarker.INSTANCE;

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

    static Checkpoints create(DurableStore store, String executionId, String flowId,
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

    String executionId() {
        return executionId;
    }

    long revision() {
        return revision;
    }

    /** 把当前机器状态以 revision+1 CAS 提交。 */
    void commit(CheckpointReasons.Reason reason) {
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
                    new com.team4u.framework.flow.Metadata(flowId, flowVersion, executionId,
                            reason.path(), java.util.Optional.empty()),
                    snapshot.revision(), snapshot.lifecycle(), attributes));
        } catch (RuntimeException ignored) {
            // Durable observers cannot alter execution.
        }
    }

    static DurableException conflict(String executionId) {
        return new DurableException(DurableException.Error.REVISION_CONFLICT,
                "Checkpoint revision conflict for execution " + executionId);
    }

    static DurableException storeFailure(Exception cause) {
        return new DurableException(DurableException.Error.STORE_FAILURE,
                "Durable store operation failed: " + cause.getMessage(), cause);
    }
}
