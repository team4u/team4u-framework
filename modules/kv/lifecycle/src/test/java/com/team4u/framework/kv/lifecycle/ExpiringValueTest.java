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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
    public void loaderFailureOnAbsentPropagates() {
        ExpiringValue<String> broken = ExpiringValue.<String>builder(String.class)
                .store(store)
                .key("auth", "broken")
                .loader(() -> {
                    throw new IllegalStateException("third-party down");
                })
                .fixedTtl(10_000)
                .clock(clock)
                .build();

        try {
            broken.get();
            fail("absent 路径 loader 失败必须传播");
        } catch (IllegalStateException expected) {
            assertEquals("third-party down", expected.getMessage());
        }
    }

    @Test
    public void refreshAheadFailureReturnsOldValue() {
        token.get();   // token-1 已缓存

        // 进入刷新窗口，但加载器持续失败：get() 应返回旧值而非抛异常
        clock.advance(8500);
        AtomicInteger failures = new AtomicInteger();
        ExpiringValue<String> flaky = ExpiringValue.<String>builder(String.class)
                .store(store)
                .key("auth", "wechat_token")
                .loader(() -> {
                    failures.incrementAndGet();
                    throw new IllegalStateException("refresh down");
                })
                .fixedTtl(10_000)
                .refreshAhead(2000)
                .clock(clock)
                .build();

        assertEquals("续期失败不影响返回旧值", "token-1", flaky.get());
        assertEquals(1, failures.get());

        // 旧值仍有效（尚未真正过期）
        assertEquals("token-1", flaky.get());
    }

    @Test
    public void cooldownSuppressesSequentialStorm() {
        token.get();   // token-1 已缓存
        clock.advance(8500);   // 进入刷新窗口（剩余 1500ms）

        AtomicInteger failures = new AtomicInteger();
        ExpiringValue<String> flaky = ExpiringValue.<String>builder(String.class)
                .store(store)
                .key("auth", "wechat_token")
                .loader(() -> {
                    failures.incrementAndGet();
                    throw new IllegalStateException("source down");
                })
                .fixedTtl(10_000)
                .refreshAhead(2000)
                .cooldown(1000, 60_000)
                .clock(clock)
                .build();

        // 第一次 get：尝试加载并失败，进入冷却（retryAt = 8500 + 1000 = 9500）
        assertEquals("token-1", flaky.get());
        assertEquals(1, failures.get());

        // 冷却期内多次 get：时钟小幅推进但未越过 retryAt，全部跳过加载
        for (int i = 0; i < 5; i++) {
            clock.advance(100);
            assertEquals("token-1", flaky.get());
        }
        assertEquals("冷却期内不重复打源端", 1, failures.get());

        // 时钟越过冷却期（now = 9600 > 9500）：再次尝试并再次失败
        clock.advance(600);
        assertEquals("token-1", flaky.get());
        assertEquals("冷却结束后重试", 2, failures.get());
    }

    @Test
    public void localSingleflightWaitersShareSingleLoadFailure() throws Exception {
        token.get();   // token-1 已缓存
        clock.advance(8500);   // 进入刷新窗口

        int threads = 12;
        AtomicInteger failures = new AtomicInteger();
        ExpiringValue<String> flaky = ExpiringValue.<String>builder(String.class)
                .store(store)
                .key("auth", "wechat_token")
                .loader(() -> {
                    failures.incrementAndGet();
                    try {
                        // 让其余线程有时间堆叠到同一在途 future 上
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    throw new IllegalStateException("source down");
                })
                .fixedTtl(10_000)
                .refreshAhead(2000)
                .clock(clock)
                .build();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return flaky.get();
                }));
            }
            start.countDown();
            for (Future<String> result : results) {
                assertEquals("续期失败时所有等待者拿旧值", "token-1",
                        result.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals("等待者与赢家共享同一次失败，不再各自重试", 1, failures.get());
    }

    @Test
    public void asyncRefreshAheadReturnsOldValueImmediately() {
        token.get();   // token-1 已缓存
        clock.advance(8500);   // 进入刷新窗口

        // 捕获任务稍后手动执行的 executor（避免 Runnable::run 退化同步）
        List<Runnable> capturedTasks = new ArrayList<>();
        AtomicInteger loads = new AtomicInteger();
        ExpiringValue<String> async = ExpiringValue.<String>builder(String.class)
                .store(store)
                .key("auth", "wechat_token")
                .loader(() -> {
                    loads.incrementAndGet();
                    return "token-async";
                })
                .fixedTtl(10_000)
                .refreshAhead(2000)
                .refreshAheadAsync(capturedTasks::add)
                .clock(clock)
                .build();

        assertEquals("异步模式下立即返回旧值", "token-1", async.get());
        assertEquals("加载任务只提交未执行", 0, loads.get());
        assertEquals(1, capturedTasks.size());

        // 在途期间再次 get：输家直接返回旧值，不重复提交
        assertEquals("token-1", async.get());
        assertEquals(1, capturedTasks.size());

        // 执行捕获的异步任务：续期完成
        capturedTasks.get(0).run();
        assertEquals(1, loads.get());

        // 新值在下个 get() 可见（新记录剩余 TTL 充足，不再触发刷新）
        assertEquals("token-async", async.get());
        assertEquals(1, loads.get());
    }

    @Test
    public void asyncRefreshAheadFailureDoesNotPropagate() {
        token.get();   // token-1 已缓存
        clock.advance(8500);   // 进入刷新窗口

        List<Runnable> capturedTasks = new ArrayList<>();
        AtomicInteger failures = new AtomicInteger();
        ExpiringValue<String> async = ExpiringValue.<String>builder(String.class)
                .store(store)
                .key("auth", "wechat_token")
                .loader(() -> {
                    failures.incrementAndGet();
                    throw new IllegalStateException("source down");
                })
                .fixedTtl(10_000)
                .refreshAhead(2000)
                .refreshAheadAsync(capturedTasks::add)
                .clock(clock)
                .build();

        assertEquals("token-1", async.get());

        // 异步任务内部失败：只记日志与冷却，绝不传播给调用方
        capturedTasks.get(0).run();
        assertEquals(1, failures.get());

        // 冷却期内不再重复提交
        assertEquals("token-1", async.get());
        assertEquals(1, capturedTasks.size());
        assertEquals(1, failures.get());
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
