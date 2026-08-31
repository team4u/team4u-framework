package com.team4u.framework.flow.durable.kv;

import com.team4u.framework.flow.durable.DurableLifecycle;
import com.team4u.framework.flow.durable.snapshot.DurableSnapshot;
import com.team4u.framework.flow.durable.snapshot.StoredValue;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 流程快照序列化 DTO（Durable Snapshot DTO）。
 *
 * <p>提供 {@link DurableSnapshot} 与 JSON 结构之间的无损映射，包括二进制字节数组的 Base64 编解码。</p>
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
        return dto;
    }

    public DurableSnapshot toSnapshot() {
        DurableLifecycle lc = DurableLifecycle.valueOf(this.lifecycle);
        byte[] meta = this.frameMetadata != null
                ? Base64.getDecoder().decode(this.frameMetadata)
                : new byte[0];
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
                this.pendingResume
        );
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
                    ? Base64.getDecoder().decode(this.payload)
                    : new byte[0];
            return new StoredValue(this.codecId, this.codecVersion, bytes);
        }
    }
}
