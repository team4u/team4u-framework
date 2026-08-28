package com.team4u.framework.kv.memory;

import com.team4u.framework.kv.CasCapable;
import com.team4u.framework.kv.CounterCapable;
import com.team4u.framework.kv.KvEvent;
import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.ScanCapable;
import com.team4u.framework.kv.ScoredWindowCapable;
import com.team4u.framework.kv.ScoredWindowCapable.Offer;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.kv.WatchCapable;
import com.team4u.framework.kv.support.SettableClock;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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

    // ------------------------------------------------- 计数 TTL

    @Test
    public void counterWithoutTtlNeverExpires() {
        SpaceKey key = SpaceKey.of("seq", "c1");

        assertEquals(1, store.incrementAndGet(key, 1, 0));
        clock.advance(24L * 3600_000);
        assertEquals(2, store.incrementAndGet(key, 1, 0));
    }

    @Test
    public void counterExpiresAndRestartsFromZero() {
        SpaceKey key = SpaceKey.of("seq", "c2");

        assertEquals(3, store.incrementAndGet(key, 3, 1000));
        clock.advance(999);
        assertEquals(4, store.incrementAndGet(key, 1, 1000));
        clock.advance(1);
        assertEquals("expired counter restarts from zero", 2,
                store.incrementAndGet(key, 2, 1000));
        assertEquals(3, store.incrementAndGet(key, 1, 1000));
    }

    @Test
    public void counterTtlNotRefreshedByLaterIncrements() {
        SpaceKey key = SpaceKey.of("seq", "c3");

        store.incrementAndGet(key, 1, 1000);
        clock.advance(500);
        store.incrementAndGet(key, 1, 1000);
        clock.advance(500);
        assertEquals("TTL set at creation must not be refreshed", 1,
                store.incrementAndGet(key, 1, 1000));
    }

    @Test
    public void expiredCounterPrunedByPruneExpired() {
        SpaceKey expiredKey = SpaceKey.of("seq", "c-expired");
        SpaceKey aliveKey = SpaceKey.of("seq", "c-alive");

        store.incrementAndGet(expiredKey, 1, 1000);
        store.incrementAndGet(aliveKey, 1, 0);
        clock.advance(1000);

        assertEquals(1, store.pruneExpired("seq", 10));
        // 过期键被清扫后视为键不存在：从 0 重新开始
        assertEquals(1, store.incrementAndGet(expiredKey, 1, 0));
        assertEquals(2, store.incrementAndGet(aliveKey, 1, 0));
    }

    // ------------------------------------------------- 计分窗口能力

    @Test
    public void offerPrunesMembersAtOrBelowCutoff() {
        SpaceKey key = SpaceKey.of("rl", "w1");

        ScoredWindowCapable.Verdict v = store.offer(key, Offer.builder()
                .cutoffScore(100).memberScore(100)
                .members(Collections.singletonList("a")).maxCount(10).build());
        assertTrue(v.isAccepted());
        assertEquals(1, v.getCount());
        assertEquals(100L, v.getOldestScore().longValue());

        // score == cutoff 被裁剪后仅剩新成员
        v = store.offer(key, Offer.builder()
                .cutoffScore(100).memberScore(200)
                .members(Collections.singletonList("b")).maxCount(10).build());
        assertTrue(v.isAccepted());
        assertEquals(1, v.getCount());
        assertEquals(200L, v.getOldestScore().longValue());

        // score > cutoff 存活
        v = store.offer(key, Offer.builder()
                .cutoffScore(201).memberScore(300)
                .members(Collections.singletonList("c")).maxCount(10).build());
        assertTrue(v.isAccepted());
        assertEquals(1, v.getCount());
    }

    @Test
    public void offerAddsWhenWithinMaxCount() {
        SpaceKey key = SpaceKey.of("rl", "w2");

        ScoredWindowCapable.Verdict v = store.offer(key, Offer.builder()
                .cutoffScore(0).memberScore(100)
                .members(Arrays.asList("a", "b")).maxCount(2).build());
        assertTrue(v.isAccepted());
        assertEquals(2, v.getCount());
        assertEquals(100L, v.getOldestScore().longValue());
    }

    @Test
    public void offerRejectsOverLimitWithoutAdding() {
        SpaceKey key = SpaceKey.of("rl", "w3");

        assertTrue(store.offer(key, Offer.builder()
                .cutoffScore(0).memberScore(100)
                .members(Collections.singletonList("a")).maxCount(2).build()).isAccepted());

        ScoredWindowCapable.Verdict rejected = store.offer(key, Offer.builder()
                .cutoffScore(0).memberScore(200)
                .members(Arrays.asList("b", "c")).maxCount(2).build());
        assertFalse(rejected.isAccepted());
        assertEquals(1, rejected.getCount());
        assertEquals(100L, rejected.getOldestScore().longValue());

        // 拒绝不添加任何成员：后续计数不变
        ScoredWindowCapable.Verdict peek = store.offer(key, Offer.builder()
                .cutoffScore(0).maxCount(2).build());
        assertTrue(peek.isAccepted());
        assertEquals(1, peek.getCount());
    }

    @Test
    public void offerPeekNeverRejectsAndDoesNotAdd() {
        SpaceKey key = SpaceKey.of("rl", "w4");

        ScoredWindowCapable.Verdict empty = store.offer(key, Offer.builder()
                .cutoffScore(0).maxCount(1).build());
        assertTrue(empty.isAccepted());
        assertEquals(0, empty.getCount());
        assertNull(empty.getOldestScore());

        store.offer(key, Offer.builder().cutoffScore(0).memberScore(100)
                .members(Collections.singletonList("a")).maxCount(1).build());

        // 已满窗口窥探也永不拒绝，且不添加
        ScoredWindowCapable.Verdict peek = store.offer(key, Offer.builder()
                .cutoffScore(0).maxCount(1).build());
        assertTrue(peek.isAccepted());
        assertEquals(1, peek.getCount());
        assertEquals(100L, peek.getOldestScore().longValue());
    }

    @Test
    public void offerExpiresWholeKeyAfterTtl() {
        SpaceKey key = SpaceKey.of("rl", "w5");

        store.offer(key, Offer.builder().cutoffScore(0).memberScore(100)
                .members(Collections.singletonList("a")).maxCount(1).ttlMillis(1000).build());
        clock.advance(1000);

        // 整键过期：旧成员全部消失，即使 cutoff 不裁剪也从零重来
        ScoredWindowCapable.Verdict v = store.offer(key, Offer.builder()
                .cutoffScore(0).memberScore(50)
                .members(Collections.singletonList("b")).maxCount(1).ttlMillis(1000).build());
        assertTrue(v.isAccepted());
        assertEquals(1, v.getCount());
        assertEquals(50L, v.getOldestScore().longValue());
    }

    @Test
    public void offerRefreshesTtlOnEachSuccess() {
        SpaceKey key = SpaceKey.of("rl", "w6");

        store.offer(key, Offer.builder().cutoffScore(0).memberScore(100)
                .members(Collections.singletonList("a")).maxCount(10).ttlMillis(1000).build());
        clock.advance(600);
        store.offer(key, Offer.builder().cutoffScore(0).maxCount(10).ttlMillis(1000).build());
        clock.advance(600);

        // 窥探刷新了 TTL：旧成员仍存活
        ScoredWindowCapable.Verdict v = store.offer(key, Offer.builder()
                .cutoffScore(0).memberScore(200)
                .members(Collections.singletonList("b")).maxCount(10).ttlMillis(1000).build());
        assertTrue(v.isAccepted());
        assertEquals(2, v.getCount());
        assertEquals(100L, v.getOldestScore().longValue());
    }

    @Test
    public void offerOldestScoreIsMinimum() {
        SpaceKey key = SpaceKey.of("rl", "w7");

        store.offer(key, Offer.builder().cutoffScore(0).memberScore(300)
                .members(Collections.singletonList("a")).maxCount(10).build());
        store.offer(key, Offer.builder().cutoffScore(0).memberScore(100)
                .members(Collections.singletonList("b")).maxCount(10).build());
        store.offer(key, Offer.builder().cutoffScore(0).memberScore(200)
                .members(Collections.singletonList("c")).maxCount(10).build());

        ScoredWindowCapable.Verdict v = store.offer(key, Offer.builder()
                .cutoffScore(150).maxCount(10).build());
        assertEquals(2, v.getCount());
        assertEquals(200L, v.getOldestScore().longValue());
    }

    @Test
    public void offerGrowsBeyondInitialCapacity() {
        SpaceKey key = SpaceKey.of("rl", "w8");

        // 初始容量 8：验证按需扩容
        for (int i = 1; i <= 20; i++) {
            ScoredWindowCapable.Verdict v = store.offer(key, Offer.builder()
                    .cutoffScore(0).memberScore(100)
                    .members(Collections.singletonList("m" + i)).maxCount(20).build());
            assertTrue(v.isAccepted());
            assertEquals(i, v.getCount());
        }
    }

    @Test
    public void expiredWindowPrunedByPruneExpired() {
        SpaceKey expiredKey = SpaceKey.of("rl", "w-expired");
        SpaceKey aliveKey = SpaceKey.of("rl", "w-alive");

        store.offer(expiredKey, Offer.builder().cutoffScore(0).memberScore(100)
                .members(Collections.singletonList("a")).maxCount(1).ttlMillis(1000).build());
        store.offer(aliveKey, Offer.builder().cutoffScore(0).memberScore(100)
                .members(Collections.singletonList("a")).maxCount(1).ttlMillis(0).build());
        clock.advance(1000);

        // 同批清扫记录 + 计数器 + 窗口（此处仅 1 个过期窗口）
        assertEquals(1, store.pruneExpired("rl", 10));
    }

    @Test
    public void pruneExpiredCountsRecordsCountersAndWindowsTogether() {
        SpaceKey record = SpaceKey.of("mix", "r1");
        SpaceKey counterKey = SpaceKey.of("mix", "c1");
        SpaceKey windowKey = SpaceKey.of("mix", "w1");

        store.put(record, KvRecord.of("v1", 1000, clock.millis()), PutMode.SET);
        store.incrementAndGet(counterKey, 1, 1000);
        store.offer(windowKey, Offer.builder().cutoffScore(0).memberScore(1)
                .members(Collections.singletonList("a")).maxCount(1).ttlMillis(1000).build());
        clock.advance(1000);

        assertEquals(3, store.pruneExpired("mix", 10));
        assertEquals(0, store.pruneExpired("mix", 10));
    }

    @Test
    public void closeClearsCountersAndWindows() throws Exception {
        SpaceKey counterKey = SpaceKey.of("seq", "cc");
        SpaceKey windowKey = SpaceKey.of("rl", "ww");

        store.incrementAndGet(counterKey, 5, 0);
        store.offer(windowKey, Offer.builder().cutoffScore(0).memberScore(1)
                .members(Collections.singletonList("a")).maxCount(1).build());

        store.close();

        assertEquals("counter must restart after close", 1,
                store.incrementAndGet(counterKey, 1, 0));
        ScoredWindowCapable.Verdict v = store.offer(windowKey, Offer.builder()
                .cutoffScore(0).maxCount(1).build());
        assertEquals("window must restart after close", 0, v.getCount());
    }

    // ------------------------------------------------- 能力接口声明

    @Test
    public void declaresAllCapabilities() {
        assertTrue(store instanceof CasCapable);
        assertTrue(store instanceof ScanCapable);
        assertTrue(store instanceof WatchCapable);
        assertTrue(store instanceof CounterCapable);
        assertTrue(store instanceof ScoredWindowCapable);
        KvStore asStore = store;
        assertTrue(asStore instanceof AutoCloseable);
    }
}
