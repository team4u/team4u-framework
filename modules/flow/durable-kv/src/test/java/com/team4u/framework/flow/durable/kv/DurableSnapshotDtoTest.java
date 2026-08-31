package com.team4u.framework.flow.durable.kv;

import com.team4u.framework.flow.durable.DurableLifecycle;
import com.team4u.framework.flow.durable.snapshot.DurableSnapshot;
import com.team4u.framework.flow.durable.snapshot.StoredValue;
import com.team4u.framework.serializer.json.JsonUtil;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DurableSnapshotDtoTest {

    @Test
    public void roundTripActiveSnapshot() {
        Map<String, StoredValue> slots = new LinkedHashMap<>();
        slots.put("input", new StoredValue("json", 1, new byte[]{1, 2, 3, 4}));
        slots.put("order", new StoredValue("raw", 2, new byte[]{5, 6, 7}));

        DurableSnapshot original = new DurableSnapshot(
                "exec-1001",
                "flow-order",
                2,
                DurableSnapshot.CURRENT_FORMAT_ID,
                DurableSnapshot.CURRENT_FORMAT_VERSION,
                3L,
                DurableLifecycle.ACTIVE,
                new byte[]{10, 20, 30},
                slots,
                null,
                false
        );

        DurableSnapshotDto dto = DurableSnapshotDto.fromSnapshot(original);
        assertNotNull(dto);

        String json = JsonUtil.toJsonStr(dto);
        assertNotNull(json);

        DurableSnapshotDto restoredDto = JsonUtil.toBean(json, DurableSnapshotDto.class);
        assertNotNull(restoredDto);

        DurableSnapshot restored = restoredDto.toSnapshot();
        assertEquals(original, restored);
    }

    @Test
    public void roundTripSuspendedSnapshot() {
        Map<String, StoredValue> slots = new LinkedHashMap<>();
        slots.put("input", new StoredValue("json", 1, new byte[]{9, 8, 7}));

        DurableSnapshot original = new DurableSnapshot(
                "exec-1002",
                "flow-approval",
                1,
                DurableSnapshot.CURRENT_FORMAT_ID,
                DurableSnapshot.CURRENT_FORMAT_VERSION,
                1L,
                DurableLifecycle.SUSPENDED,
                new byte[]{40, 50},
                slots,
                "manager_approval",
                false
        );

        DurableSnapshotDto dto = DurableSnapshotDto.fromSnapshot(original);
        String json = JsonUtil.toJsonStr(dto);
        DurableSnapshotDto restoredDto = JsonUtil.toBean(json, DurableSnapshotDto.class);
        DurableSnapshot restored = restoredDto.toSnapshot();

        assertEquals(original, restored);
    }

    @Test
    public void nullHandling() {
        assertNull(DurableSnapshotDto.fromSnapshot(null));
        assertNull(DurableSnapshotDto.StoredValueDto.fromStoredValue(null));
    }

    @Test
    public void corruptedLifecycleIsFormatMismatch() {
        DurableSnapshotDto dto = DurableSnapshotDto.fromSnapshot(activeSnapshot());
        dto.setLifecycle("NOT_A_LIFECYCLE");
        try {
            dto.toSnapshot();
            org.junit.Assert.fail("非法 lifecycle 必须归类为 FORMAT_MISMATCH");
        } catch (com.team4u.framework.flow.durable.DurableException error) {
            assertEquals(com.team4u.framework.flow.durable.DurableException.Error.FORMAT_MISMATCH,
                    error.error());
            assertTrue(error.getMessage().contains("NOT_A_LIFECYCLE"));
        }
    }

    @Test
    public void corruptedBase64FrameMetadataIsFormatMismatch() {
        DurableSnapshotDto dto = DurableSnapshotDto.fromSnapshot(activeSnapshot());
        dto.setFrameMetadata("!!!not-base64!!!");
        try {
            dto.toSnapshot();
            org.junit.Assert.fail("非法 Base64 必须归类为 FORMAT_MISMATCH");
        } catch (com.team4u.framework.flow.durable.DurableException error) {
            assertEquals(com.team4u.framework.flow.durable.DurableException.Error.FORMAT_MISMATCH,
                    error.error());
        }
    }

    @Test
    public void corruptedSlotPayloadIsFormatMismatch() {
        DurableSnapshotDto dto = DurableSnapshotDto.fromSnapshot(activeSnapshot());
        dto.getSlots().get("input").setPayload("%%%bad-base64%%%");
        try {
            dto.toSnapshot();
            org.junit.Assert.fail("非法槽位 Base64 必须归类为 FORMAT_MISMATCH");
        } catch (com.team4u.framework.flow.durable.DurableException error) {
            assertEquals(com.team4u.framework.flow.durable.DurableException.Error.FORMAT_MISMATCH,
                    error.error());
        }
    }

    @Test
    public void corruptedFirstWakeAtTimestampIsFormatMismatch() {
        DurableSnapshotDto dto = DurableSnapshotDto.fromSnapshot(activeSnapshot());
        dto.setFirstWakeAt("not-a-timestamp");
        try {
            dto.toSnapshot();
            org.junit.Assert.fail("非法时间戳必须归类为 FORMAT_MISMATCH");
        } catch (com.team4u.framework.flow.durable.DurableException error) {
            assertEquals(com.team4u.framework.flow.durable.DurableException.Error.FORMAT_MISMATCH,
                    error.error());
        }
    }

    @Test
    public void firstWakeAtRoundTripsThroughJson() {
        // ACTIVE + firstWakeAt 的快照经 JSON 往返后仍相等（信封字段无损）
        java.time.Instant wake = java.time.Instant.now();
        DurableSnapshot original = new DurableSnapshot(
                "exec-1003", "flow-wake", 1,
                DurableSnapshot.CURRENT_FORMAT_ID, DurableSnapshot.CURRENT_FORMAT_VERSION,
                0L, DurableLifecycle.ACTIVE, new byte[]{1},
                slots(), null, false, wake);
        DurableSnapshotDto dto = DurableSnapshotDto.fromSnapshot(original);
        String json = JsonUtil.toJsonStr(dto);
        DurableSnapshot restored = JsonUtil.toBean(json, DurableSnapshotDto.class).toSnapshot();
        assertEquals(original, restored);
        assertEquals(wake, restored.firstWakeAt());
    }

    private static DurableSnapshot activeSnapshot() {
        return new DurableSnapshot(
                "exec-1004", "flow-dto", 1,
                DurableSnapshot.CURRENT_FORMAT_ID, DurableSnapshot.CURRENT_FORMAT_VERSION,
                0L, DurableLifecycle.ACTIVE, new byte[]{7},
                slots(), null, false);
    }

    private static Map<String, StoredValue> slots() {
        Map<String, StoredValue> map = new LinkedHashMap<>();
        map.put("input", new StoredValue("json", 1, new byte[]{1, 2}));
        return map;
    }
}
