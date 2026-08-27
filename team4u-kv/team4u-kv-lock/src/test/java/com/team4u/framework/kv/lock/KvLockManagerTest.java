package com.team4u.framework.kv.lock;

import com.team4u.framework.kv.KvStoreException;
import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.kv.memory.InMemoryKvStore;
import com.team4u.framework.kv.test.TestKvContext.SettableClock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

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
import static org.junit.Assert.fail;

public class KvLockManagerTest {

    private SettableClock clock;
    private InMemoryKvStore store;
    private KvLockManager manager;

    @Before
    public void setUp() {
        clock = new SettableClock(0L);
        store = new InMemoryKvStore(clock);
        manager = newManager();
    }

    private KvLockManager newManager() {
        return new KvLockManager(store, clock,
                new KvLockManager.Config().setHeartbeatIntervalMillis(3600_000));
    }

    @After
    public void tearDown() {
        manager.close();
    }

    @Test
    public void tryAcquireMutualExclusion() {
        assertNotNull(manager.tryAcquire("job", 5000));

        KvLockManager other = newManager();
        try {
            assertNull("second acquirer must fail", other.tryAcquire("job", 5000));
        } finally {
            other.close();
        }
    }

    @Test
    public void releaseAllowsImmediateReacquire() {
        try (KvLock lock = manager.tryAcquire("job", 5000)) {
            assertNotNull(lock);
        }
        assertNotNull(manager.tryAcquire("job", 5000));
    }

    @Test
    public void leaseExpiryEnablesTakeover() {
        try (KvLock ignored = manager.tryAcquire("job", 1000)) {
            assertNotNull(ignored);
            clock.advance(1000);

            KvLockManager other = newManager();
            try {
                assertNotNull("expired lock must be acquirable", other.tryAcquire("job", 5000));
            } finally {
                other.close();
            }
        }
    }

    /**
     * fencing 核心语义：锁被他人接管后，旧持有者释放不得影响新持有者
     */
    @Test
    public void staleReleaseNeverDamagesNewOwner() {
        KvLock oldLock = manager.tryAcquire("job", 1000);
        assertNotNull(oldLock);

        clock.advance(1000);
        KvLockManager other = newManager();
        try {
            KvLock newLock = other.tryAcquire("job", 5000);
            assertNotNull(newLock);

            // 旧持有者（锁已超时被接管）释放：安全空操作，新持有者不受影响
            assertFalse(oldLock.release());
            assertTrue(newLock.isHeld());
        } finally {
            other.close();
        }
    }

    @Test
    public void renewExtendsLeaseButNotOthers() {
        try (KvLock lock = manager.tryAcquire("job", 2000)) {
            assertNotNull(lock);
            clock.advance(1000);
            assertTrue(lock.renew()); // 续约到 now+2000
            clock.advance(1500);
            assertTrue("lock should still be held after renewal", lock.isHeld());

            // 他人令牌的续约必须失败
            KvLockManager other = newManager();
            try {
                KvLock loser = other.tryAcquire("job", 5000);
                assertNull(loser);
            } finally {
                other.close();
            }
        }
    }

    @Test
    public void renewFailsAfterTakeover() {
        KvLock lock = manager.tryAcquire("job", 1000);
        assertNotNull(lock);

        clock.advance(1000);
        KvLockManager other = newManager();
        try {
            assertNotNull(other.tryAcquire("job", 5000));
            assertFalse("renew must fail once taken over", lock.renew());
        } finally {
            other.close();
        }
    }

    @Test
    public void lockValueIsTokenNotGuessable() {
        manager.tryAcquire("job", 5000);
        KvRecord record = store.get(SpaceKey.of("kv.lock", "job"));
        assertNotNull(record);
        assertTrue(record.getValue().contains(":"));
    }

    @Test
    public void acquireBlocksUntilAvailableOrTimeout() throws Exception {
        KvLockManager holder = newManager();
        try (KvLock ignored = holder.tryAcquire("job", 10_000)) {
            assertNotNull(ignored);
            try {
                manager.acquire("job", 5000, 300);
                fail("should timeout");
            } catch (KvLockTimeoutException expected) {
                assertTrue(expected.getMessage().contains("job"));
            }
        } finally {
            holder.close();
        }

        // 释放后可获取
        try (KvLock lock = manager.acquire("job", 5000, 5000)) {
            assertNotNull(lock);
        }
    }

    @Test
    public void closeReleasesAllHeldLocks() {
        KvLockManager shortLived = newManager();
        assertNotNull(shortLived.tryAcquire("a", 60_000));
        assertNotNull(shortLived.tryAcquire("b", 60_000));
        shortLived.close();

        assertNull(store.get(SpaceKey.of("kv.lock", "a")));
        assertNull(store.get(SpaceKey.of("kv.lock", "b")));
        try {
            shortLived.tryAcquire("c", 1000);
            fail("closed manager must reject new acquire");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("closed"));
        }
    }

    @Test
    public void nonCasStoreFailsFast() {
        try {
            new KvLockManager(new NonCasStore());
            fail("should reject non-CasCapable store");
        } catch (KvStoreException expected) {
            assertTrue(expected.getMessage().contains("CasCapable"));
        }
    }

    @Test
    public void concurrentAcquireSingleWinner() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger winners = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        // 竞争期内不关闭任何管理器：胜者 close 会释放锁，放行后续竞争者
        java.util.List<KvLockManager> managers =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                KvLockManager m = newManager();
                managers.add(m);
                pool.submit(() -> {
                    start.await();
                    if (m.tryAcquire("race", 30_000) != null) {
                        winners.incrementAndGet();
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        } finally {
            managers.forEach(KvLockManager::close);
            pool.shutdownNow();
        }
        assertEquals(1, winners.get());
    }

    /**
     * 不支持 CAS 的存储桩
     */
    static class NonCasStore implements com.team4u.framework.kv.KvStore {

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
}
