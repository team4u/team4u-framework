package com.team4u.framework.flow.durable.snapshot;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import com.team4u.framework.flow.durable.DurableLifecycle;

/**
 * 不可变耐久化快照信封（Immutable Durable Snapshot Envelope）。
 *
 * <p>表示流程实例在特定时间点的完整序列化状态，用于跨进程持久化与崩溃恢复。
 * 快照内只包含元数据（Format / Version / Revision / Lifecycle）与业务载荷插槽（{@link StoredValue}），绝不包含任何可执行代码或动态代理引用。</p>
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
public final class DurableSnapshot {
    /** 当前快照格式唯一标识。 */
    public static final String CURRENT_FORMAT_ID = "team4u-typed-flow-durable";
    /** 当前快照格式版本号。 */
    public static final int CURRENT_FORMAT_VERSION = 1;

    /** 流程执行实例唯一 ID。 */
    private final String executionId;
    /** 流程定义唯一 ID。 */
    private final String flowId;
    /** 流程定义版本号。 */
    private final int flowVersion;
    /** 快照编码格式 ID。 */
    private final String formatId;
    /** 快照编码格式版本。 */
    private final int formatVersion;
    /** 乐观锁单调递增版本号（从 0 开始）。 */
    private final long revision;
    /** 流程生命周期阶段。 */
    private final DurableLifecycle lifecycle;
    /** 执行帧栈紧凑二进制拓扑元数据。 */
    private final byte[] frameMetadata;
    /** 业务插槽字典（按 SlotRole 索引）。 */
    private final Map<String, StoredValue> slots;
    /** 正在等待的挂起点名称（若处于 SUSPENDED 或待消费信号状态）。 */
    private final String awaitingPoint;
    /** 是否包含待消费的恢复信号（Pending Resume Signal）。 */
    private final boolean pendingResume;

    /**
     * 构造不可变快照对象并严格校验生命周期约束不变式。
     *
     * @param executionId   流程实例 ID
     * @param flowId        流程 ID
     * @param flowVersion   流程版本
     * @param formatId      格式 ID
     * @param formatVersion 格式版本
     * @param revision      版本号
     * @param lifecycle     生命周期
     * @param frameMetadata 帧拓扑字节数组
     * @param slots         业务数据插槽映射
     * @param awaitingPoint 挂起点名称
     * @param pendingResume 是否包含挂起信号
     */
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

    public byte[] frameMetadata() { return frameMetadata.clone(); }

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
