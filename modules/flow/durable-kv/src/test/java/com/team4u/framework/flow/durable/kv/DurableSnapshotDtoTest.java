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
}
