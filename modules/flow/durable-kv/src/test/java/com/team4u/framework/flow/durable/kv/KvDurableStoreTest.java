package com.team4u.framework.flow.durable.kv;

import com.team4u.framework.flow.durable.DurableLifecycle;
import com.team4u.framework.flow.durable.snapshot.DurableSnapshot;
import com.team4u.framework.flow.durable.snapshot.StoredValue;
import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.kv.memory.InMemoryKvStore;
import com.team4u.framework.kv.observed.ObservedStore;
import org.junit.Before;
import org.junit.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class KvDurableStoreTest {

    private InMemoryKvStore kvStore;
    private KvDurableStore durableStore;

    @Before
    public void setUp() {
        kvStore = new InMemoryKvStore();
        durableStore = new KvDurableStore(kvStore);
    }

    private DurableSnapshot createSnapshot(String execId, long revision) {
        return new DurableSnapshot(
                execId,
                "order-flow",
                1,
                DurableSnapshot.CURRENT_FORMAT_ID,
                DurableSnapshot.CURRENT_FORMAT_VERSION,
                revision,
                DurableLifecycle.ACTIVE,
                new byte[]{1, 2, 3},
                Collections.singletonMap("in", new StoredValue("json", 1, new byte[]{10, 20})),
                null,
                false
        );
    }

    @Test
    public void initialCreateAndLoad() {
        String execId = "order-001";
        DurableSnapshot initSnapshot = createSnapshot(execId, 0L);

        // 1. 首次创建 expectedRevision = -1
        boolean created = durableStore.compareAndSet(execId, -1L, initSnapshot);
        assertTrue(created);

        // 2. 加载验证
        Optional<DurableSnapshot> loaded = durableStore.load(execId);
        assertTrue(loaded.isPresent());
        assertEquals(initSnapshot, loaded.get());

        // 3. 重复首次创建返回 false
        boolean duplicate = durableStore.compareAndSet(execId, -1L, initSnapshot);
        assertFalse(duplicate);
    }

    @Test
    public void casUpdateLifecycle() {
        String execId = "order-002";
        DurableSnapshot v0 = createSnapshot(execId, 0L);
        DurableSnapshot v1 = createSnapshot(execId, 1L);
        DurableSnapshot v2 = createSnapshot(execId, 2L);

        // 创建 v0
        assertTrue(durableStore.compareAndSet(execId, -1L, v0));

        // 期望版本不匹配 (期望 1，但实际是 0) -> false
        assertFalse(durableStore.compareAndSet(execId, 1L, v2));

        // 期望版本匹配 (期望 0，更新到 v1) -> true
        assertTrue(durableStore.compareAndSet(execId, 0L, v1));
        assertEquals(1L, durableStore.load(execId).get().revision());

        // 再次以 0 更新 -> false (已被更新为 1)
        assertFalse(durableStore.compareAndSet(execId, 0L, v1));

        // 期望 1 更新到 v2 -> true
        assertTrue(durableStore.compareAndSet(execId, 1L, v2));
        assertEquals(2L, durableStore.load(execId).get().revision());
    }

    @Test
    public void updateNonExistentReturnsFalse() {
        String execId = "non-existent";
        DurableSnapshot v1 = createSnapshot(execId, 1L);
        assertFalse(durableStore.compareAndSet(execId, 0L, v1));
        assertFalse(durableStore.load(execId).isPresent());
    }

    @Test
    public void customSpaceAndTtl() {
        Clock fixedClock = Clock.fixed(Instant.ofEpochMilli(1_000_000L), ZoneOffset.UTC);
        InMemoryKvStore clockKvStore = new InMemoryKvStore(fixedClock);
        KvDurableStore customStore = new KvDurableStore(clockKvStore, "custom_flow", 5000L, fixedClock);

        assertEquals("custom_flow", customStore.space());
        assertEquals(5000L, customStore.ttlMillis());
        assertEquals(fixedClock, customStore.clock());

        String execId = "order-ttl";
        DurableSnapshot snapshot = createSnapshot(execId, 0L);
        assertTrue(customStore.compareAndSet(execId, -1L, snapshot));

        // 验证底层的 KvRecord 写入了 TTL
        KvRecord record = clockKvStore.get(SpaceKey.of("custom_flow", execId));
        assertNotNull(record);
        assertEquals(1_000_000L + 5000L, record.getExpireAt());
    }

    @Test
    public void rejectsNonCasCapableStore() {
        KvStore nonCasStore = new KvStore() {
            @Override
            public KvRecord get(SpaceKey key) { return null; }
            @Override
            public boolean put(SpaceKey key, KvRecord record, PutMode mode) { return false; }
            @Override
            public boolean remove(SpaceKey key) { return false; }
            @Override
            public boolean expire(SpaceKey key, long ttlMillis) { return false; }
        };

        try {
            new KvDurableStore(nonCasStore);
            fail("Should reject non-CasCapable store");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("CasCapable"));
        }
    }

    @Test
    public void supportsDecoratorChain() {
        ObservedStore observed = new ObservedStore(kvStore);
        KvDurableStore store = new KvDurableStore(observed);

        String execId = "order-obs";
        DurableSnapshot snapshot = createSnapshot(execId, 0L);
        assertTrue(store.compareAndSet(execId, -1L, snapshot));
        assertTrue(store.load(execId).isPresent());
    }

    @Test
    public void invalidArgumentsValidation() {
        DurableSnapshot snapshot = createSnapshot("exec-1", 0L);

        // blank executionId
        try {
            durableStore.load("");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {}

        // null update
        try {
            durableStore.compareAndSet("exec-1", -1L, null);
            fail("Expected NullPointerException");
        } catch (NullPointerException expected) {}

        // executionId mismatch
        try {
            durableStore.compareAndSet("exec-2", -1L, snapshot);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {}

        // expectedRevision < -1
        try {
            durableStore.compareAndSet("exec-1", -2L, snapshot);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {}

        // revision != expectedRevision + 1
        try {
            durableStore.compareAndSet("exec-1", 0L, snapshot); // snapshot.revision is 0, expected is 0 (should be 1)
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {}
    }
}
