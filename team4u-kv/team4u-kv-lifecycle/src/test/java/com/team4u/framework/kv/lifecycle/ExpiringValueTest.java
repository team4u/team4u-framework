package com.team4u.framework.kv.lifecycle;

import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.kv.lock.KvLockManager;
import com.team4u.framework.kv.memory.InMemoryKvStore;
import com.team4u.framework.kv.test.TestKvContext.SettableClock;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ExpiringValueTest {

    private SettableClock clock;
    private InMemoryKvStore store;
    private AtomicInteger loadCount;
    private ExpiringValue<String> token;

    @Before
    public void setUp() {
        clock = new SettableClock(0L);
        store = new InMemoryKvStore(clock);
        loadCount = new AtomicInteger();
        token = ExpiringValue.<String>builder(String.class)
                .store(store)
                .key("auth", "wechat_token")
                .loader(() -> {
                    loadCount.incrementAndGet();
                    return "token-" + loadCount.get();
                })
                .fixedTtl(10_000)
                .refreshAhead(2000)
                .clock(clock)
                .build();
    }

    @Test
    public void absentValueLoadsOnce() {
        assertEquals("token-1", token.get());
        assertEquals(1, loadCount.get());
        assertEquals("token-1", token.get());
        assertEquals("命中缓存不再加载", 1, loadCount.get());
    }

    @Test
    public void expiredValueReloads() {
        token.get();
        clock.advance(10_000);
        assertEquals("token-2", token.get());
        assertEquals(2, loadCount.get());
    }

    @Test
    public void refreshAheadRenewsBeforeExpiry() {
        token.get();
        // 进入刷新窗口（剩余 2000ms 内）：触发续期，返回旧值，随后新值生效
        clock.advance(8500);
        assertEquals("token-1", token.get());
        assertEquals("刷新窗口内同步续期", 2, loadCount.get());

        // 续期后又有完整 TTL
        clock.advance(5000);
        assertEquals("token-2", token.get());
        assertEquals(2, loadCount.get());
    }

    @Test
    public void forceRefreshAlwaysLoads() {
        token.get();
        assertEquals("token-2", token.refresh());
        assertEquals(2, loadCount.get());
    }

    @Test
    public void localSingleflightLoadsOnceUnderConcurrency() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    start.await();
                    token.get();
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals("并发下仅加载一次", 1, loadCount.get());
    }

    @Test
    public void clusterScopeUsesLock() throws Exception {
        SettableClock lockClock = new SettableClock(0L);
        InMemoryKvStore lockStore = new InMemoryKvStore(lockClock);
        KvLockManager lockManager = new KvLockManager(lockStore, lockClock,
                new KvLockManager.Config().setHeartbeatIntervalMillis(3600_000));
        try {
            com.team4u.framework.kv.SpaceKey lockKey = com.team4u.framework.kv.SpaceKey.of(
                    "kv.lock", "auth.cluster_token.refresh");
            ExpiringValue<String> clustered = ExpiringValue.<String>builder(String.class)
                    .store(store)
                    .key("auth", "cluster_token")
                    .loader(() -> {
                        // 加载执行于刷新锁持有期间，锁记录必然存在
                        assertNotNull("刷新锁应在加载期间被持有", lockStore.get(lockKey));
                        loadCount.incrementAndGet();
                        return "ct-" + loadCount.get();
                    })
                    .fixedTtl(10_000)
                    .scope(ExpiringValue.Scope.CLUSTER)
                    .lockManager(lockManager)
                    .clock(clock)
                    .build();

            assertEquals("ct-1", clustered.get());
            assertEquals(1, loadCount.get());

            // 加载完成、锁释放后，锁记录被清理
            assertNull("加载结束后刷新锁应释放", lockStore.get(lockKey));
        } finally {
            lockManager.close();
        }
    }

    @Test
    public void clusterScopeRequiresLockManager() {
        try {
            ExpiringValue.<String>builder(String.class)
                    .store(store)
                    .key("auth", "k")
                    .loader(() -> "v")
                    .fixedTtl(1000)
                    .scope(ExpiringValue.Scope.CLUSTER)
                    .clock(clock)
                    .build();
            fail("CLUSTER scope without lock manager must fail");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void valuePersistedInStoreAsRecord() {
        token.get();
        KvRecord record = store.get(SpaceKey.of("auth", "wechat_token"));
        assertNotNull(record);
        assertEquals("\"token-1\"", record.getValue());
        assertEquals(10_000, record.getExpireAt());
    }

    @Test
    public void nonStringValuesRoundTrip() {
        ExpiringValue<User> users = ExpiringValue.<User>builder(User.class)
                .store(store)
                .key("user", "u1")
                .loader(() -> new User("admin", 30))
                .ttlOf(u -> 60_000)
                .clock(clock)
                .build();

        User user = users.get();
        assertEquals("admin", user.getName());
        assertEquals(30, user.getAge());
        assertEquals("User{age=30, name='admin'}", users.get().toString());
    }

    public static class User {
        private String name;
        private int age;

        public User() {
        }

        public User(String name, int age) {
            this.name = name;
            this.age = age;
        }

        public String getName() {
            return name;
        }

        public int getAge() {
            return age;
        }

        @Override
        public String toString() {
            return "User{age=" + age + ", name='" + name + "'}";
        }
    }
}
