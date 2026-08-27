package com.team4u.framework.kv.memory;

import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.SpaceKey;
import org.junit.Before;
import org.junit.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

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
        // 过期条目同时被惰性清理
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
    public void closeClearsAllData() throws Exception {
        store.put(SpaceKey.of("user", "u1"), KvRecord.of("v1"), PutMode.SET);
        store.close();
        assertNull(store.get(SpaceKey.of("user", "u1")));
    }

    /**
     * 可手动推进时间的测试时钟
     */
    public static class SettableClock extends Clock {

        private long millis;

        public SettableClock(long initialMillis) {
            this.millis = initialMillis;
        }

        public void advance(long deltaMillis) {
            millis += deltaMillis;
        }

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }
}
