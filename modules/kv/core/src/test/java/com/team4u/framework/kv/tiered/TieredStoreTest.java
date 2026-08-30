package com.team4u.framework.kv.tiered;

import com.team4u.framework.base.cache.Cache;
import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.kv.support.SettableClock;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TieredStoreTest {

    private SettableClock clock;
    private CountingL2 l2;
    private TieredStore store;

    @Before
    public void setUp() {
        clock = new SettableClock(0L);
        l2 = new CountingL2(clock);
        // 使用无 TTL 的 L1，验证读取路径按记录自身过期时间兜底的正确性
        store = new TieredStore(l2, new NeverEvictCache(), new TieredStore.Config(), clock);
    }

    @Test
    public void readMissGoesThroughToL2AndBackfills() {
        SpaceKey key = SpaceKey.of("user", "u1");
        l2.put(key, KvRecord.of("v1"), PutMode.SET);

        assertEquals("v1", store.get(key).getValue());
        assertEquals(1, l2.getCounter.get());

        // 第二次读取命中 L1，不再访问 L2
        assertEquals("v1", store.get(key).getValue());
        assertEquals(1, l2.getCounter.get());
    }

    @Test
    public void expiredRecordIsNotServedFromL1() {
        SpaceKey key = SpaceKey.of("user", "u1");
        l2.put(key, KvRecord.of("v1", 1000, clock.millis()), PutMode.SET);

        // 回填 L1 后，记录按自身过期时间过期
        store.get(key);
        clock.advance(1000);

        // L1 条目虽未被缓存实现淘汰，但读取按记录过期时间兜底，穿透回源 L2
        assertNull(store.get(key));
        assertEquals(2, l2.getCounter.get());
    }

    @Test
    public void putWritesThroughBothTiers() {
        SpaceKey key = SpaceKey.of("user", "u1");
        store.put(key, KvRecord.of("v1"), PutMode.SET);

        // 写直通后读取命中 L1，不访问 L2
        assertEquals("v1", store.get(key).getValue());
        assertEquals(0, l2.getCounter.get());

        // 清空 L1 后仍能从 L2 读到，证明写已穿透到 L2
        store.evictAll();
        assertEquals("v1", store.get(key).getValue());
        assertEquals(1, l2.getCounter.get());
    }

    @Test
    public void putIfAbsentFailureDoesNotPolluteL1() {
        SpaceKey key = SpaceKey.of("idem", "o1");
        l2.put(key, KvRecord.of("existing"), PutMode.SET);

        assertFalse(store.put(key, KvRecord.of("new"), PutMode.IF_ABSENT));
        assertEquals("existing", l2.get(key).getValue());

        // L1 未被污染：仍从既有值回填，读取到 existing 而非 new
        assertEquals("existing", store.get(key).getValue());
        assertEquals(2, l2.getCounter.get()); // 本测试直接读 L2 一次 + store.get 穿透一次
    }

    @Test
    public void negativeCacheBlocksRepeatMisses() {
        store = new TieredStore(
                l2, new NeverEvictCache(),
                new TieredStore.Config().setNegativeTtlMillis(1000), clock);
        SpaceKey key = SpaceKey.of("user", "u1");

        assertNull(store.get(key));
        assertEquals(1, l2.getCounter.get());

        // 负缓存窗口内：同键读取不再穿透 L2（即使 L2 已有数据）
        l2.put(key, KvRecord.of("v1"), PutMode.SET);
        assertNull(store.get(key));
        assertEquals(1, l2.getCounter.get());

        // 窗口结束：回源 L2 读到新值
        clock.advance(1000);
        assertEquals("v1", store.get(key).getValue());
        assertEquals(2, l2.getCounter.get());
    }

    @Test
    public void tombstoneExpiresExactlyAtBoundary() {
        store = new TieredStore(
                l2, new NeverEvictCache(),
                new TieredStore.Config().setTombstoneTtlMillis(1000), clock);
        SpaceKey key = SpaceKey.of("user", "u1");

        store.remove(key);
        // 恰好到达边界（now == tombstoneUntil）：墓碑失效，回源 L2
        clock.advance(1000);
        assertNull(store.get(key));
        assertEquals(1, l2.getCounter.get());
    }

    @Test
    public void backfillRespectsEffectiveTombstone() {
        store = new TieredStore(
                l2, new NeverEvictCache(),
                new TieredStore.Config().setTombstoneTtlMillis(60_000), clock);
        SpaceKey key = SpaceKey.of("user", "u1");
        store.put(key, KvRecord.of("v1"), PutMode.SET);
        store.evictAll(); // L1 清空，模拟回填前未命中

        // L2 有值 + L1 有墓碑：回填应放弃，不覆盖墓碑
        store.remove(key);
        l2.put(key, KvRecord.of("stale"), PutMode.SET);
        assertNull(store.get(key));
    }

    @Test
    public void l1FailureDegradesToEvict() {
        SpaceKey key = SpaceKey.of("user", "u1");
        ThrowingPutCache throwing = new ThrowingPutCache();
        TieredStore tiered = new TieredStore(l2, throwing, new TieredStore.Config(), clock);

        // L1 写失败降级为失效，不阻断写直通
        assertTrue(tiered.put(key, KvRecord.of("v1"), PutMode.SET));
        assertEquals("v1", l2.get(key).getValue());
        assertTrue(throwing.removed.contains(key));
    }

    @Test
    public void closeClearsL1AndClosesL2() throws Exception {
        AutoCloseableL2 closable = new AutoCloseableL2(clock);
        TieredStore tiered = new TieredStore(closable, new NeverEvictCache(),
                new TieredStore.Config(), clock);
        tiered.put(SpaceKey.of("user", "u1"), KvRecord.of("v1"), PutMode.SET);

        tiered.close();

        assertTrue(closable.closed);
        assertEquals(0, closable.size());
    }

    @Test
    public void removeWithoutTombstoneInvalidatesL1() {
        store = new TieredStore(l2, new NeverEvictCache(), new TieredStore.Config(), clock);
        SpaceKey key = SpaceKey.of("user", "u1");

        store.put(key, KvRecord.of("v1"), PutMode.SET);
        store.remove(key);

        assertNull(store.get(key));
    }

    @Test
    public void tombstoneBlocksReadAndPreventsResurrection() {
        store = new TieredStore(
                l2, new NeverEvictCache(),
                new TieredStore.Config().setTombstoneTtlMillis(1000), clock);
        SpaceKey key = SpaceKey.of("user", "u1");

        store.put(key, KvRecord.of("v1"), PutMode.SET);
        assertTrue(store.remove(key));

        // 墓碑窗口内：不再访问 L2（0 次 get），读取判定为不存在
        assertEquals(0, l2.getCounter.get());
        assertNull(store.get(key));
        assertEquals(0, l2.getCounter.get());

        // 墓碑过期后：读取回退到 L2
        clock.advance(1001);
        l2.put(key, KvRecord.of("v2"), PutMode.SET);
        assertEquals("v2", store.get(key).getValue());
        assertEquals(1, l2.getCounter.get());
    }

    @Test
    public void putOverwritesTombstone() {
        store = new TieredStore(
                l2, new NeverEvictCache(),
                new TieredStore.Config().setTombstoneTtlMillis(60_000), clock);
        SpaceKey key = SpaceKey.of("user", "u1");

        store.remove(key);
        store.put(key, KvRecord.of("v1"), PutMode.SET);

        assertEquals("v1", store.get(key).getValue());
        assertEquals(0, l2.getCounter.get());
    }

    @Test
    public void expireInvalidatesL1() {
        SpaceKey key = SpaceKey.of("user", "u1");
        store.put(key, KvRecord.of("v1", 1000, clock.millis()), PutMode.SET);

        clock.advance(500);
        assertTrue(store.expire(key, 5000));

        // L1 已失效，续期后的记录自 L2 回填
        assertEquals("v1", store.get(key).getValue());
        assertEquals(1, l2.getCounter.get());
        assertEquals(5000 + 500, store.get(key).getExpireAt());
    }

    @Test
    public void evictAllClearsL1Only() {
        SpaceKey key = SpaceKey.of("user", "u1");
        store.put(key, KvRecord.of("v1"), PutMode.SET);
        store.evictAll();

        assertNotNull(store.get(key));
        assertEquals("v1", l2.get(key).getValue());
    }

    @Test
    public void defaultTimedCacheL1Works() {
        TieredStore timed = new TieredStore(l2, 1000, new TieredStore.Config());
        SpaceKey key = SpaceKey.of("user", "u1");
        timed.put(key, KvRecord.of("v1"), PutMode.SET);

        assertEquals("v1", timed.get(key).getValue());
    }

    /**
     * 无淘汰策略的 L1 缓存桩：条目只增不减，用于验证读取路径的过期兜底
     */
    static class NeverEvictCache implements Cache<SpaceKey, TieredStore.Entry> {

        private final Map<SpaceKey, TieredStore.Entry> map = new HashMap<>();

        @Override
        public TieredStore.Entry get(SpaceKey key) {
            return map.get(key);
        }

        @Override
        public void put(SpaceKey key, TieredStore.Entry value) {
            map.put(key, value);
        }

        @Override
        public void remove(SpaceKey key) {
            map.remove(key);
        }

        @Override
        public void clear() {
            map.clear();
        }

        @Override
        public int size() {
            return map.size();
        }
    }

    /**
     * 统计读取次数的 L2 存储桩，内部委托给共享时钟的内存存储
     */
    static class CountingL2 implements KvStore {

        final KvStore delegate;
        final AtomicInteger getCounter = new AtomicInteger();

        CountingL2(java.time.Clock clock) {
            this.delegate = new com.team4u.framework.kv.memory.InMemoryKvStore(clock);
        }

        @Override
        public KvRecord get(SpaceKey key) {
            getCounter.incrementAndGet();
            return delegate.get(key);
        }

        @Override
        public boolean put(SpaceKey key, KvRecord record, PutMode mode) {
            return delegate.put(key, record, mode);
        }

        @Override
        public boolean remove(SpaceKey key) {
            return delegate.remove(key);
        }

        @Override
        public boolean expire(SpaceKey key, long ttlMillis) {
            return delegate.expire(key, ttlMillis);
        }
    }

    /**
     * 写入即抛异常的 L1 缓存桩：验证 L1 失败降级为失效
     */
    static class ThrowingPutCache extends NeverEvictCache {

        final java.util.Set<SpaceKey> removed = java.util.Collections.newSetFromMap(
                new java.util.concurrent.ConcurrentHashMap<>());

        @Override
        public void put(SpaceKey key, TieredStore.Entry value) {
            throw new IllegalStateException("L1 broken");
        }

        @Override
        public void remove(SpaceKey key) {
            removed.add(key);
            super.remove(key);
        }
    }

    /**
     * 可感知关闭的 L2 存储桩
     */
    static class AutoCloseableL2 extends CountingL2 implements AutoCloseable {

        volatile boolean closed;

        AutoCloseableL2(java.time.Clock clock) {
            super(clock);
        }

        @Override
        public void close() {
            closed = true;
            ((com.team4u.framework.kv.memory.InMemoryKvStore) delegate).close();
        }

        int size() {
            return ((com.team4u.framework.kv.memory.InMemoryKvStore) delegate).size();
        }
    }

}
