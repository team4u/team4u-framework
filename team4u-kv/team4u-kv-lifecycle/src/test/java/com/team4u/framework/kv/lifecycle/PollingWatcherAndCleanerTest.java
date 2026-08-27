package com.team4u.framework.kv.lifecycle;

import com.team4u.framework.kv.KvEvent;
import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.kv.lock.KvLock;
import com.team4u.framework.kv.lock.KvLockManager;
import com.team4u.framework.kv.memory.InMemoryKvStore;
import com.team4u.framework.kv.test.TestKvContext.SettableClock;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PollingWatcherAndCleanerTest {

    @Test
    public void pollingWatcherDetectsPutAndRemove() throws Exception {
        InMemoryKvStore store = new InMemoryKvStore();
        List<KvEvent> events = new ArrayList<>();
        // 两阶段等待：确保 put 与 remove 分别被不同轮询周期观测到
        CountDownLatch putSeen = new CountDownLatch(1);
        CountDownLatch removeSeen = new CountDownLatch(1);

        try (PollingWatcher watcher = new PollingWatcher(store, 20)) {
            try (AutoCloseable ignored = watcher.watch("task", event -> {
                events.add(event);
                if (event.getType() == KvEvent.Type.PUT) {
                    putSeen.countDown();
                } else {
                    removeSeen.countDown();
                }
            })) {
                store.put(SpaceKey.of("task", "t1"), KvRecord.of("v1"), PutMode.SET);
                assertTrue("PUT event should arrive within polling window",
                        putSeen.await(5, TimeUnit.SECONDS));

                store.remove(SpaceKey.of("task", "t1"));
                assertTrue("REMOVE event should arrive within polling window",
                        removeSeen.await(5, TimeUnit.SECONDS));
            }
        }

        assertTrue(events.stream().anyMatch(e -> e.getType() == KvEvent.Type.PUT));
        assertTrue(events.stream().anyMatch(e -> e.getType() == KvEvent.Type.REMOVE));
    }

    @Test
    public void pollingWatcherIgnoresOtherSpaces() throws Exception {
        InMemoryKvStore store = new InMemoryKvStore();
        List<KvEvent> events = new ArrayList<>();
        CountDownLatch received = new CountDownLatch(1);

        try (PollingWatcher watcher = new PollingWatcher(store, 20)) {
            try (AutoCloseable ignored = watcher.watch("task", event -> {
                events.add(event);
                received.countDown();
            })) {
                store.put(SpaceKey.of("other", "k"), KvRecord.of("v"), PutMode.SET);
                store.put(SpaceKey.of("task", "t1"), KvRecord.of("v1"), PutMode.SET);

                assertTrue(received.await(5, TimeUnit.SECONDS));
            }
        }

        assertEquals(1, events.size());
        assertEquals("t1", events.get(0).getKey().getKey());
    }

    @Test
    public void pollingWatcherRequiresScanCapableStore() {
        try {
            new PollingWatcher(new NonScannableStore(), 100);
            org.junit.Assert.fail("should reject non-scan store");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("ScanCapable"));
        }
    }

    @Test
    public void cleanerPrunesExpiredByRegisteredSpace() {
        SettableClock clock = new SettableClock(0L);
        InMemoryKvStore store = new InMemoryKvStore(clock);
        store.put(SpaceKey.of("task", "t1"), KvRecord.of("v1", 1000, clock.millis()), PutMode.SET);
        store.put(SpaceKey.of("task", "t2"), KvRecord.of("v2"), PutMode.SET);
        store.put(SpaceKey.of("other", "t3"), KvRecord.of("v3"), PutMode.SET);
        clock.advance(1000);

        KvCleaner cleaner = new KvCleaner(60_000, 100)
                .addStore(store)
                .addSpace("task");
        try {
            cleaner.runOnceQuietly();
            assertEquals("注册键空间的过期数据被清理", 1, store.scan("task").size());
            assertEquals("未注册的键空间不受影响", 1, store.scan("other").size());
            assertEquals("task 的过期残留已被物理清理", 0, store.pruneExpired("task", 10));
        } finally {
            cleaner.close();
        }
    }

    @Test
    public void cleanerSkipsNativeTtlStores() {
        NativeTtlStub store = new NativeTtlStub();
        KvCleaner cleaner = new KvCleaner(60_000, 100).addStore(store).addSpace("a");
        try {
            cleaner.runOnceQuietly();
            assertEquals("原生 TTL 存储不清理", 0, store.pruneCalls.get());
        } finally {
            cleaner.close();
        }
    }

    @Test
    public void cleanerWithLockSkipsWhenHeldByOther() throws Exception {
        SettableClock clock = new SettableClock(0L);
        InMemoryKvStore store = new InMemoryKvStore(clock);

        KvLockManager holder = new KvLockManager(store, clock,
                new KvLockManager.Config().setHeartbeatIntervalMillis(3600_000));
        KvCleaner cleaner = null;
        try {
            KvLock held = holder.tryAcquire("shared.cleaner", 60_000);
            assertTrue(held != null);

            cleaner = new KvCleaner("shared", 60_000, 100,
                    new KvLockManager(store, clock, new KvLockManager.Config()
                            .setHeartbeatIntervalMillis(3600_000)
                            .setOwnerId("other-instance")))
                    .addStore(store)
                    .addSpace("task");

            store.put(SpaceKey.of("task", "t1"),
                    KvRecord.of("v1", 1000, clock.millis()), PutMode.SET);
            clock.advance(1000);

            cleaner.runOnceQuietly();
            assertEquals("锁被他人持有，本轮跳过清理：过期记录仍在原处",
                    1, store.pruneExpired("task", 10));
        } finally {
            if (cleaner != null) {
                cleaner.close();
            }
            holder.close();
        }
    }

    /**
     * 不支持扫描的存储桩
     */
    static class NonScannableStore implements KvStore {

        @Override
        public KvRecord get(SpaceKey key) {
            return null;
        }

        @Override
        public boolean put(SpaceKey key, KvRecord record, PutMode mode) {
            return true;
        }

        @Override
        public boolean remove(SpaceKey key) {
            return false;
        }

        @Override
        public boolean expire(SpaceKey key, long ttlMillis) {
            return false;
        }
    }

    /**
     * 原生 TTL 存储桩：清理器应跳过
     */
    static class NativeTtlStub implements KvStore, com.team4u.framework.kv.ScanCapable,
            com.team4u.framework.kv.NativeTtlCapable {

        final java.util.concurrent.atomic.AtomicInteger pruneCalls =
                new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public KvRecord get(SpaceKey key) {
            return null;
        }

        @Override
        public boolean put(SpaceKey key, KvRecord record, PutMode mode) {
            return true;
        }

        @Override
        public boolean remove(SpaceKey key) {
            return false;
        }

        @Override
        public boolean expire(SpaceKey key, long ttlMillis) {
            return false;
        }

        @Override
        public java.util.List<SpaceKey> scan(String space) {
            return java.util.Collections.emptyList();
        }

        @Override
        public int pruneExpired(String space, int maxBatch) {
            return pruneCalls.incrementAndGet();
        }
    }
}
