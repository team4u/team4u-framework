package com.team4u.framework.kv.memory;

import com.team4u.framework.kv.CasCapable;
import com.team4u.framework.kv.KvEvent;
import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.ScanCapable;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.kv.WatchCapable;
import com.team4u.framework.kv.support.SettableClock;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class InMemoryKvStoreTest {

    private SettableClock clock;
    private InMemoryKvStore store;

    @Before
    public void setUp() {
        clock = new SettableClock(0L);
        store = new InMemoryKvStore(clock);
    }

    @Test
    public void putAndGet() {
        SpaceKey key = SpaceKey.of("user", "u1");

        store.put(key, KvRecord.of("v1"), PutMode.SET);
        assertEquals("v1", store.get(key).getValue());
    }

    @Test
    public void spacesAreIsolated() {
        store.put(SpaceKey.of("a", "k"), KvRecord.of("va"), PutMode.SET);
        store.put(SpaceKey.of("b", "k"), KvRecord.of("vb"), PutMode.SET);

        assertEquals("va", store.get(SpaceKey.of("a", "k")).getValue());
        assertEquals("vb", store.get(SpaceKey.of("b", "k")).getValue());
    }

    @Test
    public void expiredRecordIsInvisible() {
        SpaceKey key = SpaceKey.of("user", "u1");
        store.put(key, KvRecord.of("v1", 1000, clock.millis()), PutMode.SET);

        clock.advance(1000);
        assertNull(store.get(key));
        assertEquals(0, store.size());
    }

    @Test
    public void putIfAbsentIsAtomic() {
        SpaceKey key = SpaceKey.of("idem", "o1");

        assertTrue(store.put(key, KvRecord.of("1"), PutMode.IF_ABSENT));
        assertFalse(store.put(key, KvRecord.of("2"), PutMode.IF_ABSENT));
        assertEquals("1", store.get(key).getValue());
    }

    @Test
    public void putIfAbsentOverwritesExpiredRecord() {
        SpaceKey key = SpaceKey.of("idem", "o1");
        store.put(key, KvRecord.of("old", 1000, clock.millis()), PutMode.IF_ABSENT);

        clock.advance(1000);
        assertTrue(store.put(key, KvRecord.of("new"), PutMode.IF_ABSENT));
        assertEquals("new", store.get(key).getValue());
    }

    @Test
    public void removeReturnsExistence() {
        SpaceKey key = SpaceKey.of("user", "u1");
        assertFalse(store.remove(key));

        store.put(key, KvRecord.of("v1"), PutMode.SET);
        assertTrue(store.remove(key));
        assertNull(store.get(key));
    }

    @Test
    public void expireRenewsTtlAndKeepsValue() {
        SpaceKey key = SpaceKey.of("user", "u1");
        store.put(key, KvRecord.of("v1", 1000, clock.millis()), PutMode.SET);

        clock.advance(500);
        assertTrue(store.expire(key, 2000));
        assertEquals("v1", store.get(key).getValue());

        clock.advance(1500);
        assertEquals("v1", store.get(key).getValue());

        clock.advance(500);
        assertNull(store.get(key));
    }

    @Test
    public void expireOnMissingKeyReturnsFalse() {
        assertFalse(store.expire(SpaceKey.of("user", "missing"), 1000));
    }

    @Test
    public void sizePrunesExpiredEntries() {
        store.put(SpaceKey.of("user", "u1"), KvRecord.of("v1", 1000, clock.millis()), PutMode.SET);
        store.put(SpaceKey.of("user", "u2"), KvRecord.of("v2"), PutMode.SET);

        clock.advance(1000);
        assertEquals(1, store.size());
    }

    @Test
    public void closeClearsAllData() throws Exception {
        store.put(SpaceKey.of("user", "u1"), KvRecord.of("v1"), PutMode.SET);
        store.close();
        assertNull(store.get(SpaceKey.of("user", "u1")));
    }

    // ------------------------------------------------- CAS 能力

    @Test
    public void compareAndSetMatchesValueAtomically() {
        SpaceKey key = SpaceKey.of("lock", "job1");
        store.put(key, KvRecord.of("token-a"), PutMode.SET);

        assertTrue(store.compareAndSet(key, "token-a", KvRecord.of("token-b")));
        assertEquals("token-b", store.get(key).getValue());
        assertFalse(store.compareAndSet(key, "token-a", KvRecord.of("token-c")));
        assertEquals("token-b", store.get(key).getValue());
    }

    @Test
    public void compareAndRemoveFencesOwnership() {
        SpaceKey key = SpaceKey.of("lock", "job1");
        store.put(key, KvRecord.of("token-a"), PutMode.SET);

        assertFalse(store.compareAndRemove(key, "token-b"));
        assertEquals("token-a", store.get(key).getValue());

        assertTrue(store.compareAndRemove(key, "token-a"));
        assertNull(store.get(key));
    }

    @Test
    public void casOnExpiredKeyFails() {
        SpaceKey key = SpaceKey.of("lock", "job1");
        store.put(key, KvRecord.of("token-a", 1000, clock.millis()), PutMode.SET);

        clock.advance(1000);
        assertFalse(store.compareAndSet(key, "token-a", KvRecord.of("token-b")));
        assertFalse(store.compareAndRemove(key, "token-a"));
    }

    // ------------------------------------------------- 扫描能力

    @Test
    public void scanFiltersSpaceAndExpiry() {
        store.put(SpaceKey.of("a", "k1"), KvRecord.of("v1"), PutMode.SET);
        store.put(SpaceKey.of("a", "k2"), KvRecord.of("v2", 1000, clock.millis()), PutMode.SET);
        store.put(SpaceKey.of("b", "k3"), KvRecord.of("v3"), PutMode.SET);
        clock.advance(1000);

        assertEquals(1, store.scan("a").size());
        assertEquals(SpaceKey.of("a", "k1"), store.scan("a").get(0));
    }

    @Test
    public void pruneExpiredRemovesBatch() {
        store.put(SpaceKey.of("a", "k1"), KvRecord.of("v1", 1000, clock.millis()), PutMode.SET);
        store.put(SpaceKey.of("a", "k2"), KvRecord.of("v2", 1000, clock.millis()), PutMode.SET);
        store.put(SpaceKey.of("a", "k3"), KvRecord.of("v3"), PutMode.SET);
        clock.advance(1000);

        assertEquals(1, store.pruneExpired("a", 1));
        assertEquals(1, store.pruneExpired("a", 10));
        assertEquals(1, store.scan("a").size());
    }

    // ------------------------------------------------- 订阅能力

    @Test
    public void watchDeliversPutAndRemoveEvents() throws Exception {
        List<KvEvent> events = new ArrayList<>();
        SpaceKey key = SpaceKey.of("task", "t1");

        try (AutoCloseable ignored = store.watch("task", events::add)) {
            store.put(key, KvRecord.of("v1"), PutMode.SET);
            store.put(key, KvRecord.of("v2"), PutMode.SET);
            store.remove(key);
        }

        assertEquals(3, events.size());
        assertEquals(KvEvent.Type.PUT, events.get(0).getType());
        assertEquals("v1", events.get(0).getNewValue());
        assertEquals(KvEvent.Type.PUT, events.get(1).getType());
        assertEquals(KvEvent.Type.REMOVE, events.get(2).getType());
    }

    @Test
    public void watchExpiryDeliversRemoveEvent() throws Exception {
        List<KvEvent> events = new ArrayList<>();
        SpaceKey key = SpaceKey.of("task", "t1");

        try (AutoCloseable ignored = store.watch("task", events::add)) {
            store.put(key, KvRecord.of("v1", 1000, clock.millis()), PutMode.SET);
            clock.advance(1000);
            store.get(key); // 惰性过期触发事件
        }

        assertEquals(2, events.size());
        assertEquals(KvEvent.Type.REMOVE, events.get(1).getType());
    }

    @Test
    public void watchIsolationAndListenerFailure() throws Exception {
        List<KvEvent> events = new ArrayList<>();
        SpaceKey otherSpaceKey = SpaceKey.of("other", "k");

        try (AutoCloseable ignored = store.watch("task", e -> {
            throw new IllegalStateException("boom");
        }); AutoCloseable ignored2 = store.watch("task", events::add)) {
            store.put(SpaceKey.of("task", "t1"), KvRecord.of("v1"), PutMode.SET);
            store.put(otherSpaceKey, KvRecord.of("v2"), PutMode.SET);
        }

        assertEquals(1, events.size());
    }

    // ------------------------------------------------- 能力接口声明

    @Test
    public void declaresAllCapabilities() {
        assertTrue(store instanceof CasCapable);
        assertTrue(store instanceof ScanCapable);
        assertTrue(store instanceof WatchCapable);
        KvStore asStore = store;
        assertTrue(asStore instanceof AutoCloseable);
    }
}
