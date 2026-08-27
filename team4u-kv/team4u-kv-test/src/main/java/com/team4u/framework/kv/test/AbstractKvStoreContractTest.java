package com.team4u.framework.kv.test;

import com.team4u.framework.kv.CasCapable;
import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.ScanCapable;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.kv.WatchCapable;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * KV 存储行为契约测试基类
 * <p>
 * 任何 {@link KvStore} 实现（memory/jdbc/redis...）继承本类并实现
 * {@link #createStore()} 与 {@link #nowMillis()}，即可跑同一套行为契约，
 * 保证「过期语义、SETNX 语义、CAS 语义、扫描、订阅」在多后端间一致
 * ——把「内存实现与生产实现行为一致」从文档承诺变成 CI 强制
 * （对齐 team4u-lease-test 的契约测试惯例）。
 * </p>
 * 时间控制：实现须提供「当前时间毫秒」并可推进时间（虚拟时钟），
 * 无法虚拟时间的实现可覆写 {@link #advanceMillis(long)} 为真实 sleep。
 *
 * @author jay.wu
 */
public abstract class AbstractKvStoreContractTest {

    protected KvStore store;

    /**
     * 创建被测存储。setUp 时调用，tearDown 时关闭（若 AutoCloseable）
     */
    protected abstract KvStore createStore();

    /**
     * 当前时间（epoch 毫秒）。测试用虚拟时钟实现
     */
    protected abstract long nowMillis();

    /**
     * 推进时间（毫秒）。虚拟时钟直接累加，真实时钟实现可 sleep
     */
    protected void advanceMillis(long millis) {
    }

    @Before
    public void setUpStore() {
        store = createStore();
    }

    @After
    public void tearDownStore() throws Exception {
        if (store instanceof AutoCloseable) {
            ((AutoCloseable) store).close();
        }
    }

    // ------------------------------------------------- 基础读写

    @Test
    public void contractPutAndGet() {
        SpaceKey key = SpaceKey.of("contract", "k1");
        assertTrue(store.put(key, KvRecord.of("v1"), PutMode.SET));
        assertEquals("v1", store.get(key).getValue());
        assertEquals(0, store.get(key).getExpireAt());
    }

    @Test
    public void contractSpaceIsolation() {
        store.put(SpaceKey.of("a", "k"), KvRecord.of("va"), PutMode.SET);
        store.put(SpaceKey.of("b", "k"), KvRecord.of("vb"), PutMode.SET);
        assertEquals("va", store.get(SpaceKey.of("a", "k")).getValue());
        assertEquals("vb", store.get(SpaceKey.of("b", "k")).getValue());
    }

    @Test
    public void contractRemove() {
        SpaceKey key = SpaceKey.of("contract", "k1");
        assertFalse(store.remove(key));
        store.put(key, KvRecord.of("v1"), PutMode.SET);
        assertTrue(store.remove(key));
        assertNull(store.get(key));
    }

    // ------------------------------------------------- 过期语义

    @Test
    public void contractExpiredRecordInvisible() {
        SpaceKey key = SpaceKey.of("contract", "k1");
        store.put(key, KvRecord.of("v1", 1000, nowMillis()), PutMode.SET);
        advanceMillis(1000);
        assertNull(store.get(key));
    }

    @Test
    public void contractGetReturnsAccurateExpireAt() {
        SpaceKey key = SpaceKey.of("contract", "k1");
        long now = nowMillis();
        store.put(key, KvRecord.of("v1", 5000, now), PutMode.SET);
        KvRecord record = store.get(key);
        assertNotNull(record);
        assertTrue("expireAt must be accurate, was " + record.getExpireAt(),
                Math.abs(record.getExpireAt() - (now + 5000)) <= 50);
    }

    @Test
    public void contractPutIfAbsentOverwritesExpired() {
        SpaceKey key = SpaceKey.of("contract", "k1");
        store.put(key, KvRecord.of("old", 1000, nowMillis()), PutMode.IF_ABSENT);
        advanceMillis(1000);
        assertTrue(store.put(key, KvRecord.of("new"), PutMode.IF_ABSENT));
        assertEquals("new", store.get(key).getValue());
    }

    @Test
    public void contractExpireRenewsAndKeepsValue() {
        SpaceKey key = SpaceKey.of("contract", "k1");
        store.put(key, KvRecord.of("v1", 1000, nowMillis()), PutMode.SET);

        advanceMillis(500);
        assertTrue(store.expire(key, 2000));
        assertEquals("v1", store.get(key).getValue());

        advanceMillis(1500);
        assertNotNull(store.get(key));

        advanceMillis(600);
        assertNull(store.get(key));
    }

    // ------------------------------------------------- 并发原子性

    @Test
    public void concurrentPutIfAbsentSingleWinner() throws Exception {
        SpaceKey key = SpaceKey.of("contract", "idem");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger winners = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    start.await();
                    if (store.put(key, KvRecord.of("winner"), PutMode.IF_ABSENT)) {
                        winners.incrementAndGet();
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals("IF_ABSENT must be atomic: exactly one winner", 1, winners.get());
    }

    // ------------------------------------------------- 能力接口（按实现存在性执行）

    @Test
    public void contractCasIfSupported() {
        if (!(store instanceof CasCapable)) {
            return;
        }
        CasCapable cas = (CasCapable) store;
        SpaceKey key = SpaceKey.of("contract", "cas");
        store.put(key, KvRecord.of("token-a"), PutMode.SET);

        assertTrue(cas.compareAndSet(key, "token-a", KvRecord.of("token-b")));
        assertEquals("token-b", store.get(key).getValue());
        assertFalse(cas.compareAndSet(key, "token-a", KvRecord.of("token-c")));
        assertEquals("token-b", store.get(key).getValue());
        assertTrue(cas.compareAndRemove(key, "token-b"));
        assertNull(store.get(key));
        assertFalse("CAS on missing key must fail", cas.compareAndRemove(key, "token-b"));
    }

    @Test
    public void contractCasRespectsExpiry() {
        if (!(store instanceof CasCapable)) {
            return;
        }
        CasCapable cas = (CasCapable) store;
        SpaceKey key = SpaceKey.of("contract", "cas-exp");
        store.put(key, KvRecord.of("token-a", 1000, nowMillis()), PutMode.SET);

        advanceMillis(1000);
        assertFalse("CAS on expired key must fail",
                cas.compareAndSet(key, "token-a", KvRecord.of("token-b")));
    }

    @Test
    public void contractScanIfSupported() {
        if (!(store instanceof ScanCapable)) {
            return;
        }
        ScanCapable scannable = (ScanCapable) store;
        store.put(SpaceKey.of("contract-scan", "k1"), KvRecord.of("v1"), PutMode.SET);
        store.put(SpaceKey.of("contract-scan", "k2"), KvRecord.of("v2"), PutMode.SET);
        store.put(SpaceKey.of("contract-other", "k3"), KvRecord.of("v3"), PutMode.SET);
        store.put(SpaceKey.of("contract-scan", "k4"),
                KvRecord.of("v4", 1000, nowMillis()), PutMode.SET);
        advanceMillis(1000);

        List<SpaceKey> keys = scannable.scan("contract-scan");
        assertEquals(2, keys.size());
    }

    @Test
    public void contractPruneExpiredIfSupported() {
        if (!(store instanceof ScanCapable)) {
            return;
        }
        ScanCapable scannable = (ScanCapable) store;
        store.put(SpaceKey.of("contract-prune", "k1"),
                KvRecord.of("v1", 1000, nowMillis()), PutMode.SET);
        store.put(SpaceKey.of("contract-prune", "k2"), KvRecord.of("v2"), PutMode.SET);
        advanceMillis(1000);

        int pruned = scannable.pruneExpired("contract-prune", 10);
        assertEquals(1, pruned);
        assertEquals(1, scannable.scan("contract-prune").size());
    }

    @Test
    public void contractWatchIfSupported() throws Exception {
        if (!(store instanceof WatchCapable)) {
            return;
        }
        WatchCapable watchable = (WatchCapable) store;
        List<String> events = new CopyOnWriteArrayList<>();
        try (AutoCloseable ignored = watchable.watch("contract-watch", event ->
                events.add(event.getType() + ":" + event.getKey().getKey()))) {
            store.put(SpaceKey.of("contract-watch", "k1"), KvRecord.of("v1"), PutMode.SET);
            store.remove(SpaceKey.of("contract-watch", "k1"));
        }
        assertTrue("watch events: " + events, events.size() >= 2);
    }
}
