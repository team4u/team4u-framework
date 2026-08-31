package com.team4u.framework.flow.durable.kv;

import com.team4u.framework.flow.durable.DurableException;
import com.team4u.framework.flow.durable.DurableLifecycle;
import com.team4u.framework.flow.durable.snapshot.DurableSnapshot;
import com.team4u.framework.flow.durable.snapshot.StoredValue;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 流程快照序列化 DTO（Durable Snapshot DTO）。
 *
 * <p>提供 {@link DurableSnapshot} 与 JSON 结构之间的无损映射，包括二进制字节数组的 Base64 编解码。
 * 反序列化遇到损坏数据（非法枚举名、非法 Base64、非法时间戳）时抛出
 * {@link DurableException.Error#FORMAT_MISMATCH}，而非裸运行时异常，便于调用方按格式错误归类处理。</p>
 *
 * @author jay.wu
 */
@Data
@NoArgsConstructor
public class DurableSnapshotDto {
    private String executionId;
    private String flowId;
    private int flowVersion;
    private String formatId;
    private int formatVersion;
    private long revision;
    private String lifecycle;
    private String frameMetadata;
    private Map<String, StoredValueDto> slots;
    private String awaitingPoint;
    private boolean pendingResume;
    /** 最早的定时唤醒时刻（ISO-8601，可空；仅 ACTIVE 快照非空，与快照信封 firstWakeAt 冗余字段对应）。 */
    private String firstWakeAt;

    public static DurableSnapshotDto fromSnapshot(DurableSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        DurableSnapshotDto dto = new DurableSnapshotDto();
        dto.setExecutionId(snapshot.executionId());
        dto.setFlowId(snapshot.flowId());
        dto.setFlowVersion(snapshot.flowVersion());
        dto.setFormatId(snapshot.formatId());
        dto.setFormatVersion(snapshot.formatVersion());
        dto.setRevision(snapshot.revision());
        dto.setLifecycle(snapshot.lifecycle().name());
        dto.setFrameMetadata(snapshot.frameMetadata() != null
                ? Base64.getEncoder().encodeToString(snapshot.frameMetadata())
                : null);

        Map<String, StoredValueDto> slotMap = new LinkedHashMap<>();
        if (snapshot.slots() != null) {
            for (Map.Entry<String, StoredValue> entry : snapshot.slots().entrySet()) {
                slotMap.put(entry.getKey(), StoredValueDto.fromStoredValue(entry.getValue()));
            }
        }
        dto.setSlots(slotMap);
        dto.setAwaitingPoint(snapshot.awaitingPoint());
        dto.setPendingResume(snapshot.pendingResume());
        dto.setFirstWakeAt(snapshot.firstWakeAt() != null
                ? snapshot.firstWakeAt().toString() : null);
        return dto;
    }

    public DurableSnapshot toSnapshot() {
        DurableLifecycle lc = parseLifecycle(this.lifecycle);
        byte[] meta = this.frameMetadata != null
                ? decodeBase64(this.frameMetadata, "frameMetadata")
                : new byte[0];
        Instant firstWakeAt = this.firstWakeAt != null
                ? parseInstant(this.firstWakeAt) : null;
        Map<String, StoredValue> slotMap = new LinkedHashMap<>();
        if (this.slots != null) {
            for (Map.Entry<String, StoredValueDto> entry : this.slots.entrySet()) {
                if (entry.getValue() != null) {
                    slotMap.put(entry.getKey(), entry.getValue().toStoredValue());
                }
            }
        }
        return new DurableSnapshot(
                this.executionId,
                this.flowId,
                this.flowVersion,
                this.formatId,
                this.formatVersion,
                this.revision,
                lc,
                meta,
                slotMap,
                this.awaitingPoint,
                this.pendingResume,
                firstWakeAt
        );
    }

    private static DurableLifecycle parseLifecycle(String name) {
        if (name == null) {
            throw formatMismatch("snapshot lifecycle is missing");
        }
        try {
            return DurableLifecycle.valueOf(name);
        } catch (IllegalArgumentException error) {
            throw formatMismatch("Unknown snapshot lifecycle: " + name, error);
        }
    }

    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException error) {
            throw formatMismatch("Invalid firstWakeAt timestamp: " + value, error);
        }
    }

    private static byte[] decodeBase64(String value, String field) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException error) {
            throw formatMismatch("Invalid Base64 payload in field " + field, error);
        }
    }

    private static DurableException formatMismatch(String message) {
        return new DurableException(DurableException.Error.FORMAT_MISMATCH, message);
    }

    private static DurableException formatMismatch(String message, Throwable cause) {
        return new DurableException(DurableException.Error.FORMAT_MISMATCH, message, cause);
    }

    /**
     * 业务插槽值序列化 DTO。
     */
    @Data
    @NoArgsConstructor
    public static class StoredValueDto {
        private String codecId;
        private int codecVersion;
        private String payload;

        public static StoredValueDto fromStoredValue(StoredValue value) {
            if (value == null) {
                return null;
            }
            StoredValueDto dto = new StoredValueDto();
            dto.setCodecId(value.codecId());
            dto.setCodecVersion(value.codecVersion());
            dto.setPayload(value.payload() != null
                    ? Base64.getEncoder().encodeToString(value.payload())
                    : null);
            return dto;
        }

        public StoredValue toStoredValue() {
            byte[] bytes = this.payload != null
                    ? decodeBase64(this.payload, "slot payload")
                    : new byte[0];
            return new StoredValue(this.codecId, this.codecVersion, bytes);
        }
    }
}
