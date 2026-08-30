package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.StopReason;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 流程持久化可恢复快照信封。
 *
 * @author jay.wu
 */
public final class DurableSnapshot {

    public static final String DEFAULT_FORMAT_ID = "team4u-flow-durable";
    public static final int DEFAULT_FORMAT_VERSION = 1;

    private final String flowId;
    private final int flowVersion;
    private final String executionId;
    private final String formatId;
    private final int formatVersion;
    private final long revision;
    private final DurableLifecycle lifecycle;
    private final FrameState frameState;
    private final Map<String, StoredValue> slots;
    private final StopReason stopReason;
    private final DurableFailure failure;

    public DurableSnapshot(String flowId, int flowVersion, String executionId,
                           String formatId, int formatVersion, long revision,
                           DurableLifecycle lifecycle, FrameState frameState,
                           Map<String, StoredValue> slots, StopReason stopReason,
                           DurableFailure failure) {
        this.flowId = Objects.requireNonNull(flowId, "flowId must not be null");
        this.flowVersion = flowVersion;
        this.executionId = Objects.requireNonNull(executionId, "executionId must not be null");
        this.formatId = formatId != null ? formatId : DEFAULT_FORMAT_ID;
        this.formatVersion = formatVersion > 0 ? formatVersion : DEFAULT_FORMAT_VERSION;
        this.revision = revision;
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        this.frameState = frameState != null ? frameState : FrameState.initial();
        this.slots = slots != null ? Collections.unmodifiableMap(new LinkedHashMap<>(slots)) : Collections.emptyMap();
        this.stopReason = stopReason;
        this.failure = failure;
    }

    public static DurableSnapshot initial(String flowId, int flowVersion, String executionId, StoredValue inputSlot) {
        Map<String, StoredValue> slots = new LinkedHashMap<>();
        if (inputSlot != null) {
            slots.put("input", inputSlot);
            slots.put("active", inputSlot);
        }
        return new DurableSnapshot(flowId, flowVersion, executionId, DEFAULT_FORMAT_ID, DEFAULT_FORMAT_VERSION,
                1L, DurableLifecycle.ACTIVE, FrameState.initial(), slots, null, null);
    }

    public String flowId() { return flowId; }
    public int flowVersion() { return flowVersion; }
    public String executionId() { return executionId; }
    public String formatId() { return formatId; }
    public int formatVersion() { return formatVersion; }
    public long revision() { return revision; }
    public DurableLifecycle lifecycle() { return lifecycle; }
    public FrameState frameState() { return frameState; }
    public Map<String, StoredValue> slots() { return slots; }
    public StopReason stopReason() { return stopReason; }
    public DurableFailure failure() { return failure; }

    public StoredValue getSlot(String slotName) {
        return slots.get(slotName);
    }

    public DurableSnapshot withRevision(long newRevision) {
        return new DurableSnapshot(flowId, flowVersion, executionId, formatId, formatVersion, newRevision,
                lifecycle, frameState, slots, stopReason, failure);
    }

    public DurableSnapshot withLifecycle(DurableLifecycle newLifecycle) {
        return new DurableSnapshot(flowId, flowVersion, executionId, formatId, formatVersion, revision + 1,
                newLifecycle, frameState, slots, stopReason, failure);
    }

    public DurableSnapshot withFrameState(FrameState newFrameState) {
        return new DurableSnapshot(flowId, flowVersion, executionId, formatId, formatVersion, revision,
                lifecycle, newFrameState, slots, stopReason, failure);
    }

    public DurableSnapshot withSlot(String slotName, StoredValue value) {
        Map<String, StoredValue> newSlots = new LinkedHashMap<>(slots);
        newSlots.put(slotName, value);
        return new DurableSnapshot(flowId, flowVersion, executionId, formatId, formatVersion, revision,
                lifecycle, frameState, newSlots, stopReason, failure);
    }

    public DurableSnapshot withCheckpoint(int nextCursor, String slotName, StoredValue value) {
        Map<String, StoredValue> newSlots = new LinkedHashMap<>(slots);
        if (slotName != null && value != null) {
            newSlots.put(slotName, value);
        }
        FrameState newFrame = frameState.withCursor(nextCursor);
        return new DurableSnapshot(flowId, flowVersion, executionId, formatId, formatVersion, revision + 1,
                lifecycle, newFrame, newSlots, stopReason, failure);
    }

    public DurableSnapshot withStopped(StopReason reason) {
        return new DurableSnapshot(flowId, flowVersion, executionId, formatId, formatVersion, revision + 1,
                DurableLifecycle.STOPPED, frameState, slots, reason, null);
    }

    public DurableSnapshot withFailed(DurableFailure newFailure) {
        return new DurableSnapshot(flowId, flowVersion, executionId, formatId, formatVersion, revision + 1,
                DurableLifecycle.FAILED, frameState, slots, null, newFailure);
    }

    public DurableSnapshot withCompleted(StoredValue outputValue) {
        Map<String, StoredValue> newSlots = new LinkedHashMap<>(slots);
        if (outputValue != null) {
            newSlots.put("output", outputValue);
        }
        return new DurableSnapshot(flowId, flowVersion, executionId, formatId, formatVersion, revision + 1,
                DurableLifecycle.COMPLETED, frameState, newSlots, null, null);
    }

    public DurableSnapshot withCancelled() {
        return new DurableSnapshot(flowId, flowVersion, executionId, formatId, formatVersion, revision + 1,
                DurableLifecycle.CANCELLED, frameState, slots, stopReason, failure);
    }

    public DurableSnapshot withRetryActive() {
        return new DurableSnapshot(flowId, flowVersion, executionId, formatId, formatVersion, revision + 1,
                DurableLifecycle.ACTIVE, frameState, slots, null, null);
    }
}
