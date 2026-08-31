package com.team4u.framework.flow.durable;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable durable envelope. Framework metadata and encoded value slots are the
 * only persisted payloads; executable callbacks never belong in a snapshot.
 */
public final class DurableSnapshot {
    public static final String CURRENT_FORMAT_ID = "team4u-typed-flow-durable";
    public static final int CURRENT_FORMAT_VERSION = 1;

    private final String executionId;
    private final String flowId;
    private final int flowVersion;
    private final String formatId;
    private final int formatVersion;
    private final long revision;
    private final DurableLifecycle lifecycle;
    private final byte[] frameMetadata;
    private final Map<String, StoredValue> slots;
    private final String awaitingPoint;
    private final boolean pendingResume;

    public DurableSnapshot(String executionId, String flowId, int flowVersion,
                           String formatId, int formatVersion, long revision,
                           DurableLifecycle lifecycle, byte[] frameMetadata,
                           Map<String, StoredValue> slots, String awaitingPoint,
                           boolean pendingResume) {
        this.executionId = text(executionId, "executionId");
        this.flowId = text(flowId, "flowId");
        if (flowVersion < 1) throw new IllegalArgumentException("flowVersion must be positive");
        this.flowVersion = flowVersion;
        this.formatId = text(formatId, "formatId");
        if (formatVersion < 1) throw new IllegalArgumentException("formatVersion must be positive");
        this.formatVersion = formatVersion;
        if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
        this.revision = revision;
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        this.frameMetadata = Objects.requireNonNull(
                frameMetadata, "frameMetadata must not be null").clone();
        Objects.requireNonNull(slots, "slots must not be null");
        LinkedHashMap<String, StoredValue> copy = new LinkedHashMap<String, StoredValue>();
        for (Map.Entry<String, StoredValue> entry : slots.entrySet()) {
            copy.put(text(entry.getKey(), "slot role"), Objects.requireNonNull(
                    entry.getValue(), "stored slot must not be null"));
        }
        this.slots = Collections.unmodifiableMap(copy);
        this.awaitingPoint = awaitingPoint == null ? null : text(awaitingPoint, "awaitingPoint");
        this.pendingResume = pendingResume;
        validateLifecycle();
    }

    private void validateLifecycle() {
        if (lifecycle == DurableLifecycle.SUSPENDED) {
            if (awaitingPoint == null || pendingResume) {
                throw new IllegalArgumentException(
                        "SUSPENDED snapshot requires an awaiting point and no pending signal");
            }
        } else if (lifecycle == DurableLifecycle.ACTIVE) {
            if (pendingResume != (awaitingPoint != null)) {
                throw new IllegalArgumentException(
                        "ACTIVE awaiting state must contain exactly one pending signal");
            }
        } else if (awaitingPoint != null || pendingResume) {
            throw new IllegalArgumentException(
                    "terminal snapshot must not contain resume state");
        }
    }

    public String executionId() { return executionId; }
    public String flowId() { return flowId; }
    public int flowVersion() { return flowVersion; }
    public String formatId() { return formatId; }
    public int formatVersion() { return formatVersion; }
    public long revision() { return revision; }
    public DurableLifecycle lifecycle() { return lifecycle; }
    public byte[] frameMetadata() { return frameMetadata.clone(); }
    public Map<String, StoredValue> slots() { return slots; }
    public String awaitingPoint() { return awaitingPoint; }
    public boolean pendingResume() { return pendingResume; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof DurableSnapshot)) return false;
        DurableSnapshot that = (DurableSnapshot) other;
        return flowVersion == that.flowVersion
                && formatVersion == that.formatVersion
                && revision == that.revision
                && pendingResume == that.pendingResume
                && executionId.equals(that.executionId)
                && flowId.equals(that.flowId)
                && formatId.equals(that.formatId)
                && lifecycle == that.lifecycle
                && Arrays.equals(frameMetadata, that.frameMetadata)
                && slots.equals(that.slots)
                && Objects.equals(awaitingPoint, that.awaitingPoint);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(executionId, flowId, flowVersion, formatId,
                formatVersion, revision, lifecycle, slots, awaitingPoint, pendingResume);
        return 31 * result + Arrays.hashCode(frameMetadata);
    }

    @Override
    public String toString() {
        return "DurableSnapshot[executionId=" + executionId + ", flowId=" + flowId
                + ", flowVersion=" + flowVersion + ", revision=" + revision
                + ", lifecycle=" + lifecycle + ", slots=" + slots.keySet() + "]";
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.trim().isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
