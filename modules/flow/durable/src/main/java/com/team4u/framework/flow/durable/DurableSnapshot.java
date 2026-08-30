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
    private final FrameState retryFrameState;

    public DurableSnapshot(String flowId, int flowVersion, String executionId,
                           String formatId, int formatVersion, long revision,
                           DurableLifecycle lifecycle, FrameState frameState,
                           Map<String, StoredValue> slots, StopReason stopReason,
                           DurableFailure failure, FrameState retryFrameState) {
        if (flowId == null || flowId.trim().isEmpty()) {
            throw new IllegalArgumentException("flowId must not be null or blank");
        }
        if (flowVersion <= 0) {
            throw new IllegalArgumentException("flowVersion must be a positive integer, got: " + flowVersion);
        }
        if (executionId == null || executionId.trim().isEmpty()) {
            throw new IllegalArgumentException("executionId must not be null or blank");
        }
        if (formatId == null || formatId.trim().isEmpty()) {
            throw new IllegalArgumentException("formatId must not be null or blank");
        }
        if (formatVersion <= 0) {
            throw new IllegalArgumentException("formatVersion must be a positive integer, got: " + formatVersion);
        }
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative, got: " + revision);
        }
        this.flowId = flowId;
        this.flowVersion = flowVersion;
        this.executionId = executionId;
        this.formatId = formatId;
        this.formatVersion = formatVersion;
        this.revision = revision;
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        this.frameState = Objects.requireNonNull(frameState, "frameState must not be null");
        Objects.requireNonNull(slots, "slots must not be null");
        this.slots = Collections.unmodifiableMap(new LinkedHashMap<>(slots));
        this.stopReason = stopReason;
        this.failure = failure;
        this.retryFrameState = retryFrameState;

        validateInvariants();
    }

    public DurableSnapshot(String flowId, int flowVersion, String executionId,
                           String formatId, int formatVersion, long revision,
                           DurableLifecycle lifecycle, FrameState frameState,
                           Map<String, StoredValue> slots, StopReason stopReason,
                           DurableFailure failure) {
        this(flowId, flowVersion, executionId, formatId, formatVersion, revision,
                lifecycle, frameState, slots, stopReason, failure, null);
    }

    private void validateInvariants() {
        switch (lifecycle) {
            case ACTIVE:
                if (stopReason != null) {
                    throw new IllegalArgumentException("ACTIVE snapshot must not contain stopReason");
                }
                if (failure != null) {
                    throw new IllegalArgumentException("ACTIVE snapshot must not contain failure");
                }
                break;
            case COMPLETED:
                if (stopReason != null) {
                    throw new IllegalArgumentException("COMPLETED snapshot must not contain stopReason");
                }
                if (failure != null) {
                    throw new IllegalArgumentException("COMPLETED snapshot must not contain failure");
                }
                break;
            case STOPPED:
                if (stopReason == null) {
                    throw new IllegalArgumentException("STOPPED snapshot must contain stopReason");
                }
                if (failure != null) {
                    throw new IllegalArgumentException("STOPPED snapshot must not contain failure");
                }
                break;
            case FAILED:
                if (failure == null) {
                    throw new IllegalArgumentException("FAILED snapshot must contain failure");
                }
                if (stopReason != null) {
                    throw new IllegalArgumentException("FAILED snapshot must not contain stopReason");
                }
                break;
            case CANCELLED:
                break;
            default:
                throw new IllegalArgumentException("Unknown lifecycle: " + lifecycle);
        }
    }

    public static DurableSnapshot initial(String flowId, int flowVersion, String executionId, StoredValue inputSlot) {
        Map<String, StoredValue> slots = new LinkedHashMap<>();
        if (inputSlot != null) {
            slots.put("input", inputSlot);
            slots.put("active", inputSlot);
        }
        return new DurableSnapshot(flowId, flowVersion, executionId, DEFAULT_FORMAT_ID, DEFAULT_FORMAT_VERSION,
                1L, DurableLifecycle.ACTIVE, FrameState.initial(), slots, null, null, null);
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
    public FrameState retryFrameState() { return retryFrameState; }

    public StoredValue getSlot(String slotName) {
        return slots.get(slotName);
    }

    public DurableSnapshot withRevision(long newRevision) {
        return new DurableSnapshot(flowId, flowVersion, executionId, formatId, formatVersion, newRevision,
                lifecycle, frameState, slots, stopReason, failure, retryFrameState);
    }

    public DurableSnapshot withLifecycle(DurableLifecycle newLifecycle) {
        return new DurableSnapshot(flowId, flowVersion, executionId, formatId, formatVersion, revision + 1,
                newLifecycle, frameState, slots, stopReason, failure, retryFrameState);
    }

    public DurableSnapshot withFrameState(FrameState newFrameState) {
        return new DurableSnapshot(flowId, flowVersion, executionId, formatId, formatVersion, revision,
                lifecycle, newFrameState, slots, stopReason, failure, retryFrameState);
    }

    public DurableSnapshot withRetryFrameState(FrameState newRetryFrameState) {
        return new DurableSnapshot(flowId, flowVersion, executionId, formatId, formatVersion, revision,
                lifecycle, frameState, slots, stopReason, failure, newRetryFrameState);
    }

    public DurableSnapshot withSlot(String slotName, StoredValue value) {
        if (slotName == null || slotName.trim().isEmpty()) {
            throw new IllegalArgumentException("slotName must not be null or blank");
        }
        Objects.requireNonNull(value, "value must not be null");
        Map<String, StoredValue> newSlots = new LinkedHashMap<>(slots);
        newSlots.put(slotName, value);
        return new DurableSnapshot(flowId, flowVersion, executionId, formatId, formatVersion, revision,
                lifecycle, frameState, newSlots, stopReason, failure, retryFrameState);
    }

    public DurableSnapshot withCheckpoint(int nextCursor, String slotName, StoredValue value) {
        Map<String, StoredValue> newSlots = new LinkedHashMap<>(slots);
        if (slotName != null && value != null) {
            newSlots.put(slotName, value);
        }
        FrameState newFrame = frameState.withCursor(nextCursor);
        return new DurableSnapshot(flowId, flowVersion, executionId, formatId, formatVersion, revision + 1,
                lifecycle, newFrame, newSlots, stopReason, failure, retryFrameState);
    }

    public DurableSnapshot withStopped(StopReason reason) {
        Objects.requireNonNull(reason, "stopReason must not be null");
        return new DurableSnapshot(flowId, flowVersion, executionId, formatId, formatVersion, revision + 1,
                DurableLifecycle.STOPPED, frameState, slots, reason, null, null);
    }

    public DurableSnapshot withFailed(DurableFailure newFailure) {
        Objects.requireNonNull(newFailure, "failure must not be null");
        FrameState targetRetry = (retryFrameState != null) ? retryFrameState : frameState;
        return new DurableSnapshot(flowId, flowVersion, executionId, formatId, formatVersion, revision + 1,
                DurableLifecycle.FAILED, frameState, slots, null, newFailure, targetRetry);
    }

    public DurableSnapshot withCompleted(StoredValue outputValue) {
        Map<String, StoredValue> newSlots = new LinkedHashMap<>(slots);
        if (outputValue != null) {
            newSlots.put("output", outputValue);
        }
        return new DurableSnapshot(flowId, flowVersion, executionId, formatId, formatVersion, revision + 1,
                DurableLifecycle.COMPLETED, frameState, newSlots, null, null, null);
    }

    public DurableSnapshot withCancelled() {
        return new DurableSnapshot(flowId, flowVersion, executionId, formatId, formatVersion, revision + 1,
                DurableLifecycle.CANCELLED, frameState, slots, stopReason, failure, retryFrameState);
    }

    public DurableSnapshot withRetryActive() {
        FrameState targetFrameState = (retryFrameState != null) ? retryFrameState : frameState;
        return new DurableSnapshot(flowId, flowVersion, executionId, formatId, formatVersion, revision + 1,
                DurableLifecycle.ACTIVE, targetFrameState, slots, null, null, null);
    }
}
