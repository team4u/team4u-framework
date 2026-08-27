package com.team4u.framework.base.refresh;

import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * RefreshableValue 契约测试（全部基于 {@link MutableClock} 虚拟时钟，后台行为测试允许 50ms 级真实等待）
 *
 * @author jay.wu
 */
public class RefreshableValueTest {

    private final MutableClock clock = new MutableClock();

    // ------------------------------------------------------------ 契约 1：并发首载单飞

    /**
     * 契约 1：50 线程同时放行并发 get()，loader（计数 + 短暂 sleep）仅执行 1 次，所有线程拿到同值
     */
    @Test(timeout = 15000)
    public void testConcurrentFirstLoadSingleFlight() throws Exception {
        AtomicInteger loadCount = new AtomicInteger();
        RefreshableValue<String> value = RefreshableValue.<String>builder()
                .name("single-flight")
                .loader(ctx -> {
                    loadCount.incrementAndGet();
                    Thread.sleep(200);
                    return "v1";
                })
                .clock(clock)
                .build();

        int threads = 50;
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    return value.get();
                }));
            }
            startGate.countDown();
            for (Future<String> future : futures) {
                Assert.assertEquals("所有线程应拿到同值", "v1", future.get(10, TimeUnit.SECONDS));
            }
            Assert.assertEquals("并发首载应只触发一次加载", 1, loadCount.get());
        } finally {
            pool.shutdownNow();
            value.close();
        }
    }

    // ------------------------------------------------------------ 契约 2：阻塞失败传播 + 冷却

    /**
     * 契约 2：阻塞路径失败抛 IllegalStateException（cause 为 loader 异常）；
     * 冷却期内 get 返回旧值且 loader 不再被调；冷却期满后恢复刷新；成功后连续失败清零
     */
    @Test(timeout = 15000)
    public void testBlockingFailurePropagationAndCooldown() {
        AtomicInteger calls = new AtomicInteger();
        RefreshableValue<String> value = RefreshableValue.<String>builder()
                .name("cooldown")
                .loader(ctx -> {
                    int n = calls.incrementAndGet();
                    if (n == 1 || n == 2) {
                        throw new IllegalStateException("boom-" + n);
                    }
                    if (n == 3) {
                        return "v1";
                    }
                    if (n == 4) {
                        throw new IllegalStateException("boom-" + n);
                    }
                    return "v2";
                })
                .refreshEvery(Duration.ofSeconds(10))
                .cooldown(Duration.ofSeconds(1), Duration.ofSeconds(60))
                .clock(clock)
                .build();

        try {
            value.get();
            Assert.fail("首载失败应抛出 IllegalStateException");
        } catch (IllegalStateException e) {
            Assert.assertEquals("boom-1", e.getCause().getMessage());
        }
        try {
            value.get();
            Assert.fail("二次失败应抛出 IllegalStateException");
        } catch (IllegalStateException e) {
            Assert.assertEquals("boom-2", e.getCause().getMessage());
        }

        // 第三次成功
        Assert.assertEquals("v1", value.get());
        RefreshableValue.Status status = value.status();
        Assert.assertEquals("成功后连续失败应清零", 0, status.getConsecutiveFailures());
        Assert.assertEquals(1, status.getVersion());
        Assert.assertEquals(1, status.getRefreshCount());
        Assert.assertEquals(2, status.getFailureCount());

        // 软死期到：阻塞刷新失败，异常传播
        clock.advanceMillis(10_000);
        try {
            value.get();
            Assert.fail("阻塞刷新失败应抛出 IllegalStateException");
        } catch (IllegalStateException e) {
            Assert.assertEquals("boom-4", e.getCause().getMessage());
        }

        long retryAt = value.status().getRetryAtMillis();
        Assert.assertTrue("失败后应进入冷却", retryAt > clock.millis());

        // 冷却期内：返回旧值，loader 不再被调
        clock.advanceMillis(500);
        Assert.assertEquals("冷却期内应返回旧值", "v1", value.get());
        Assert.assertEquals("冷却期内不应再打源端", 4, calls.get());

        // 冷却期满：恢复刷新
        clock.advanceMillis(retryAt - clock.millis() + 1);
        Assert.assertEquals("冷却期满应恢复刷新", "v2", value.get());
        Assert.assertEquals(5, calls.get());
        Assert.assertEquals("成功后连续失败应清零", 0, value.status().getConsecutiveFailures());
        value.close();
    }

    // ------------------------------------------------------------ 契约 3：swr 永不阻塞

    /**
     * 契约 3：已加载且过期后，loader 阻塞在 CountDownLatch 上，get() 立即（<500ms）返回旧值；
     * 释放 latch 后异步刷新完成，新值可见
     */
    @Test(timeout = 15000)
    public void testStaleWhileRevalidateNeverBlocks() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch loaderGate = new CountDownLatch(1);
        RefreshableValue<String> value = RefreshableValue.<String>builder()
                .name("swr")
                .loader(ctx -> {
                    if (calls.incrementAndGet() == 1) {
                        return "v1";
                    }
                    loaderGate.await();
                    return "v2";
                })
                .ttlOf(v -> Duration.ofSeconds(10))
                .staleWhileRevalidate()
                .maxStale(Duration.ofSeconds(30))
                .clock(clock)
                .build();

        Assert.assertEquals("v1", value.get());
        clock.advanceMillis(10_000); // 过期

        long begin = System.nanoTime();
        String current = value.get();
        long elapsedMs = (System.nanoTime() - begin) / 1_000_000;
        Assert.assertEquals("swr 应立即返回旧值", "v1", current);
        Assert.assertTrue("swr 不应阻塞，实际耗时 " + elapsedMs + "ms", elapsedMs < 500);

        // 释放 loader，等待后台异步刷新发布新值
        loaderGate.countDown();
        awaitUntil(5000, "异步刷新完成后新值应可见", () -> "v2".equals(value.peek()));
        Assert.assertEquals("v2", value.get());
        value.close();
    }

    // ------------------------------------------------------------ 契约 4：ttlOf + refreshAhead + maxStale 时序

    /**
     * 契约 4：ttl=10s、ahead=5s（staleAfter=load+5s）、maxStale=2s（hard=load+7s）；
     * +4s 不刷新；+5s 过期触发刷新；+8s 超硬死期且 loader 持续失败时 get 抛异常（绕过冷却）
     */
    @Test(timeout = 15000)
    public void testTtlRefreshAheadMaxStaleTiming() {
        AtomicInteger calls = new AtomicInteger();
        RuntimeException sourceDown = new RuntimeException("source-down");
        RefreshableValue<String> value = RefreshableValue.<String>builder()
                .name("ttl")
                .loader(ctx -> {
                    if (calls.incrementAndGet() == 1) {
                        return "v1";
                    }
                    throw sourceDown;
                })
                .ttlOf(v -> Duration.ofSeconds(10))
                .refreshAhead(Duration.ofSeconds(5))
                .maxStale(Duration.ofSeconds(2))
                .cooldown(Duration.ofSeconds(10), Duration.ofSeconds(60))
                .clock(clock)
                .build();

        long t0 = clock.millis();
        Assert.assertEquals("v1", value.get());

        RefreshableValue.Status status = value.status();
        Assert.assertEquals("staleAfter 应为 load + (10s - 5s)", t0 + 5_000, status.getStaleAfterMillis());
        Assert.assertEquals("hardAfter 应为 staleAfter + 2s", t0 + 7_000, status.getHardAfterMillis());

        // +4s：未到软死期，不刷新
        clock.advanceMillis(4_000);
        Assert.assertEquals("v1", value.get());
        Assert.assertEquals("未过期不应刷新", 1, calls.get());

        // +5s：恰好到软死期，触发阻塞刷新（失败传播）
        clock.advanceMillis(1_000);
        try {
            value.get();
            Assert.fail("过期后阻塞刷新失败应抛出异常");
        } catch (IllegalStateException e) {
            Assert.assertSame(sourceDown, e.getCause());
        }
        Assert.assertEquals(2, calls.get());
        // 首次失败冷却 = initial = 10s
        Assert.assertEquals("首次失败冷却应为 initial",
                t0 + 5_000 + 10_000, value.status().getRetryAtMillis());

        // 仍在冷却期：冷却兜底返回旧值
        Assert.assertEquals("冷却兜底应返回旧值", "v1", value.get());
        Assert.assertEquals(2, calls.get());

        // +8s（t0+7000 之后）：越过硬死期，绕过冷却强制重载并抛出失败异常
        clock.advanceMillis(2_000);
        try {
            value.get();
            Assert.fail("越过硬死期应绕过冷却强制重载并抛出异常");
        } catch (IllegalStateException e) {
            Assert.assertSame(sourceDown, e.getCause());
        }
        Assert.assertEquals("硬死期路径应绕过冷却直接重载", 3, calls.get());
        value.close();
    }

    // ------------------------------------------------------------ 契约 5：equals 未变

    /**
     * 契约 5：loader 返回 equals 相同的新实例时，version 不动、onChange 不触发、get 返回旧引用
     */
    @Test(timeout = 15000)
    public void testEqualsUnchangedKeepsReferenceAndVersion() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger changeEvents = new AtomicInteger();
        RefreshableValue<String> value = RefreshableValue.<String>builder()
                .name("equals")
                .loader(ctx -> {
                    calls.incrementAndGet();
                    return new String("same");
                })
                .refreshEvery(Duration.ofSeconds(10))
                .onChange((oldValue, newValue) -> changeEvents.incrementAndGet())
                .clock(clock)
                .build();

        String first = value.get();
        clock.advanceMillis(10_000);
        String second = value.get();

        Assert.assertEquals("刷新应已发生", 2, calls.get());
        Assert.assertSame("equals 未变应保留旧实例", first, second);
        Assert.assertEquals("equals 未变 version 不动", 1, value.status().getVersion());
        Assert.assertEquals("刷新计数应累加", 2, value.status().getRefreshCount());
        Assert.assertEquals("equals 未变不应触发 onChange", 0, changeEvents.get());
        value.close();
    }

    // ------------------------------------------------------------ 契约 6：onChange 隔离与 FIFO

    /**
     * 契约 6：多个 listener，其中一个抛异常 / 耗时；异常被吞不影响其他 listener 收到事件，
     * 多次变更事件顺序按 version 递增（FIFO）
     */
    @Test(timeout = 15000)
    public void testOnChangeIsolatedAndFifo() throws Exception {
        AtomicInteger seq = new AtomicInteger();
        AtomicInteger throwingCalls = new AtomicInteger();
        AtomicInteger slowCalls = new AtomicInteger();
        List<String> events = Collections.synchronizedList(new ArrayList<String>());

        RefreshableValue<String> value = RefreshableValue.<String>builder()
                .name("on-change")
                .loader(ctx -> "v" + seq.incrementAndGet())
                .onChange((oldValue, newValue) -> events.add(oldValue + "->" + newValue))
                .onChange((oldValue, newValue) -> {
                    throwingCalls.incrementAndGet();
                    throw new RuntimeException("listener boom");
                })
                .onChange((oldValue, newValue) -> {
                    try {
                        Thread.sleep(30);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    slowCalls.incrementAndGet();
                })
                .clock(clock)
                .build();

        Assert.assertEquals("v1", value.get());
        value.refresh(); // v2
        value.refresh(); // v3

        awaitUntil(5000, "应收到全部 3 个变更事件且所有 listener 均执行完毕",
                () -> events.size() == 3 && slowCalls.get() == 3 && throwingCalls.get() == 3);
        List<String> snapshot;
        synchronized (events) {
            snapshot = new ArrayList<>(events);
        }
        Assert.assertEquals("变更事件应按 version 递增（FIFO）",
                Arrays.asList("null->v1", "v1->v2", "v2->v3"), snapshot);
        Assert.assertEquals("抛异常的 listener 也应被调用 3 次", 3, throwingCalls.get());
        Assert.assertEquals("异常不应影响其他 listener 收到事件", 3, slowCalls.get());
        value.close();
    }

    // ------------------------------------------------------------ 契约 7：close 语义

    /**
     * 契约 7a：close 幂等；close 后 get 返回最后值、refresh 抛 IllegalStateException
     */
    @Test(timeout = 15000)
    public void testCloseSemantics() {
        RefreshableValue<String> value = RefreshableValue.<String>builder()
                .name("close")
                .loader(ctx -> "v1")
                .refreshEvery(Duration.ofSeconds(10))
                .clock(clock)
                .build();

        Assert.assertEquals("v1", value.get());
        value.close();
        value.close(); // 二次 close 不抛

        clock.advanceMillis(100_000); // 即便早已过期
        Assert.assertEquals("close 后 get 应返回最后值", "v1", value.get());
        try {
            value.refresh();
            Assert.fail("close 后 refresh 应抛出 IllegalStateException");
        } catch (IllegalStateException ignored) {
            // 预期
        }
        Assert.assertTrue(value.status().isClosed());
    }

    /**
     * 契约 7b：未加载就 close 的 get 抛 IllegalStateException
     */
    @Test(timeout = 15000)
    public void testGetThrowsWhenClosedBeforeLoad() {
        RefreshableValue<String> value = RefreshableValue.<String>builder()
                .name("close-before-load")
                .loader(ctx -> "v1")
                .clock(clock)
                .build();
        value.close();
        try {
            value.get();
            Assert.fail("未加载即 close 后 get 应抛出 IllegalStateException");
        } catch (IllegalStateException ignored) {
            // 预期
        }
    }

    /**
     * 契约 7c：background 短周期场景 close 后 loader 调用数不再增长（等待超过 2 个周期断言）
     */
    @Test(timeout = 15000)
    public void testCloseStopsBackgroundRefresh() throws Exception {
        clock.enableRealTimeSync();
        AtomicInteger calls = new AtomicInteger();
        RefreshableValue<String> value = RefreshableValue.<String>builder()
                .name("close-background")
                .loader(ctx -> "v" + calls.incrementAndGet())
                .refreshEvery(Duration.ofMillis(50))
                .background()
                .warmup()
                .clock(clock)
                .build();

        Thread.sleep(150); // 允许后台刷新几次
        Assert.assertTrue("后台应已触发刷新, calls=" + calls.get(), calls.get() >= 2);

        value.close();
        Thread.sleep(100); // 留出在途任务收尾时间
        int afterClose = calls.get();
        Thread.sleep(300); // 等待超过 2 个周期
        Assert.assertEquals("close 后后台不应继续加载", afterClose, calls.get());
    }

    // ------------------------------------------------------------ 契约 8：后台 tick 自愈

    /**
     * 契约 8：background + refreshEvery=50ms，loader 前 2 次抛异常后成功；
     * 最终新值 v4 被加载（证明周期未因异常终止）
     */
    @Test(timeout = 15000)
    public void testBackgroundTickSelfHealsAfterFailures() throws Exception {
        clock.enableRealTimeSync();
        AtomicInteger calls = new AtomicInteger();
        RefreshableValue<String> value = RefreshableValue.<String>builder()
                .name("self-heal")
                .loader(ctx -> {
                    int n = calls.incrementAndGet();
                    if (n == 2 || n == 3) {
                        throw new IllegalStateException("transient-" + n);
                    }
                    return n == 1 ? "v1" : "v4";
                })
                .refreshEvery(Duration.ofMillis(50))
                .background()
                .warmup()
                .cooldown(Duration.ofMillis(50), Duration.ofMillis(200))
                .clock(clock)
                .build();

        Assert.assertEquals("warmup 应已完成首载", "v1", value.peek());
        awaitUntil(5000, "后台 tick 应在两次异常后自愈并加载新值", () -> "v4".equals(value.peek()));
        Assert.assertTrue("自愈前应至少经历 2 次失败", value.status().getFailureCount() >= 2);
        Assert.assertEquals("新值发布后 version 应递增一次且保持稳定", 2, value.status().getVersion());
        Assert.assertEquals("自愈后连续失败应清零", 0, value.status().getConsecutiveFailures());
        value.close();
    }

    // ------------------------------------------------------------ 契约 9：LoadContext 正确

    /**
     * 契约 9：失败两次后成功的序列中 attempt() 依次为 0/1/2，
     * oldValue() 首次成功前为 null、第二次成功时为上一值（且成功后 attempt 重新从 0 开始）
     */
    @Test(timeout = 15000)
    public void testLoadContextAttemptAndOldValue() {
        AtomicInteger calls = new AtomicInteger();
        List<Integer> attempts = new ArrayList<>();
        List<String> oldValues = new ArrayList<>();
        RefreshableValue<String> value = RefreshableValue.<String>builder()
                .name("load-context")
                .loader(ctx -> {
                    attempts.add(ctx.attempt());
                    oldValues.add(ctx.oldValue());
                    int n = calls.incrementAndGet();
                    if (n <= 2) {
                        throw new IllegalStateException("boom-" + n);
                    }
                    if (n == 3) {
                        return "a";
                    }
                    return "b";
                })
                .clock(clock)
                .build();

        try {
            value.get();
            Assert.fail("第 1 次加载失败应抛出异常");
        } catch (IllegalStateException ignored) {
            // 预期
        }
        try {
            value.get();
            Assert.fail("第 2 次加载失败应抛出异常");
        } catch (IllegalStateException ignored) {
            // 预期
        }
        Assert.assertEquals("a", value.get());
        value.refresh();

        Assert.assertEquals("attempt 应依次为 0/1/2，成功后重新计数",
                Arrays.asList(0, 1, 2, 0), attempts);
        Assert.assertEquals("oldValue 首次成功前为 null，第二次成功时为上一值",
                Arrays.asList(null, null, null, "a"), oldValues);
        value.close();
    }

    // ------------------------------------------------------------ 契约 10：build 校验

    /**
     * 契约 10：7 类非法配置组合均抛出 IllegalArgumentException 且消息含 name（附 duration 合法性）
     */
    @Test
    public void testBuildValidation() {
        // 1. name 必填
        expectIllegal(RefreshableValue.<String>builder().loader(ctx -> "v"), "name");
        // 1. loader 必填
        expectIllegal(RefreshableValue.<String>builder().name("b10"), "b10");
        // 2. refreshEvery 与 ttlOf 至多一个
        expectIllegal(base("b10").refreshEvery(Duration.ofSeconds(1)).ttlOf(v -> Duration.ofSeconds(1)), "b10");
        // 3. refreshAhead 需已配置 ttlOf
        expectIllegal(base("b10").refreshAhead(Duration.ofSeconds(1)), "b10");
        // 4. maxStale 需已配置 freshness
        expectIllegal(base("b10").maxStale(Duration.ofSeconds(1)), "b10");
        // 4. background 需已配置 freshness
        expectIllegal(base("b10").background(), "b10");
        // 5. background + ttlOf 必须同时提供 refreshAhead
        expectIllegal(base("b10").ttlOf(v -> Duration.ofSeconds(1)).background(), "b10");
        // 6. ttlOf + staleWhileRevalidate 必须提供 maxStale
        expectIllegal(base("b10").ttlOf(v -> Duration.ofSeconds(1)).staleWhileRevalidate(), "b10");
        // 7. cooldown 参数非法（initial<=0、max<initial）
        expectIllegal(base("b10").cooldown(Duration.ZERO, Duration.ofSeconds(1)), "b10");
        expectIllegal(base("b10").cooldown(Duration.ofSeconds(5), Duration.ofSeconds(1)), "b10");
        // 7. refreshEvery 必须 > 0
        expectIllegal(base("b10").refreshEvery(Duration.ZERO), "b10");
        expectIllegal(base("b10").refreshEvery(Duration.ofSeconds(-1)), "b10");
    }

    // ------------------------------------------------------------ 契约 11：status 快照一致性

    /**
     * 契约 11：并发刷新下 status() 各字段来自同一次状态读取（version 与 loadedAt/refreshCount 匹配等）
     */
    @Test(timeout = 30000)
    public void testStatusSnapshotConsistency() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RefreshableValue<String> value = RefreshableValue.<String>builder()
                .name("status")
                .loader(ctx -> "v" + calls.incrementAndGet())
                .clock(clock)
                .build();

        Assert.assertEquals("v1", value.get());
        RefreshableValue.Status first = value.status();
        Assert.assertTrue(first.isLoaded());
        Assert.assertEquals(1, first.getVersion());
        Assert.assertEquals(1, first.getRefreshCount());
        Assert.assertTrue(first.getLoadedAtMillis() > 0);
        Assert.assertFalse("MANUAL 模式不应过期", first.isStale());
        Assert.assertEquals(0, first.getStaleMillis());
        Assert.assertEquals("无 freshness 配置时永不过期", Long.MAX_VALUE, first.getStaleAfterMillis());
        Assert.assertEquals("无 maxStale 配置时硬死期无限", Long.MAX_VALUE, first.getHardAfterMillis());

        int threads = 5;
        int perThread = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch startGate = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    for (int j = 0; j < perThread; j++) {
                        value.refresh();
                    }
                    return null;
                }));
            }
            startGate.countDown();

            // 并发刷新期间反复读取快照，断言各字段来自同一次一致的状态读取
            for (int i = 0; i < 500; i++) {
                RefreshableValue.Status s = value.status();
                Assert.assertTrue("快照应保持已加载", s.isLoaded());
                Assert.assertTrue("version 应至少为 1", s.getVersion() >= 1);
                Assert.assertTrue("快照内 refreshCount 应不小于 version（每次变更同时推进二者）",
                        s.getRefreshCount() >= s.getVersion());
                Assert.assertTrue("已加载则 loadedAt 应有效", s.getLoadedAtMillis() > 0);
                if (s.getConsecutiveFailures() == 0) {
                    Assert.assertNull("无失败则 lastError 应为 null", s.getLastError());
                }
                Assert.assertFalse("MANUAL 模式不应过期", s.isStale());
            }
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }

        RefreshableValue.Status last = value.status();
        // refresh() 契约为"在途时合并等待"：并发调用会合并为更少的实际加载，
        // 故不能断言精确次数，只断言 version 与 refreshCount 同步推进且在合理范围内
        Assert.assertEquals("每次加载均产生新值，version 与 refreshCount 应同步",
                last.getRefreshCount(), last.getVersion());
        Assert.assertTrue("至少应发生首载 + 每线程可观测的加载, refreshCount=" + last.getRefreshCount(),
                last.getRefreshCount() >= threads);
        Assert.assertTrue("合并加载数不应超过总调用数", last.getRefreshCount() <= 1 + threads * perThread);
        Assert.assertEquals(0, last.getConsecutiveFailures());
        value.close();
    }

    // ------------------------------------------------------------ 契约 12：loader 返回 null

    /**
     * 契约 12：loader 返回 null 抛出 IllegalArgumentException（消息含 name）
     */
    @Test(timeout = 15000)
    public void testLoaderReturningNullThrowsIllegalArgument() {
        RefreshableValue<String> value = RefreshableValue.<String>builder()
                .name("null-loader")
                .loader(ctx -> null)
                .clock(clock)
                .build();
        try {
            value.get();
            Assert.fail("loader 返回 null 应抛出 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue("消息应说明 null 原因: " + e.getMessage(),
                    e.getMessage().contains("Loader must not return null"));
            Assert.assertTrue("消息应包含 name: " + e.getMessage(), e.getMessage().contains("null-loader"));
        }
        value.close();
    }

    // ------------------------------------------------------------ 附加：warmup 契约

    /**
     * 附加：warmup 在 build 时同步加载；失败异常（包装后）从 build() 抛出且 cause 保留
     */
    @Test(timeout = 15000)
    public void testWarmupLoadsAtBuildAndFailurePropagates() {
        RefreshableValue<String> warmed = RefreshableValue.<String>builder()
                .name("warmup-ok")
                .loader(ctx -> "v1")
                .warmup()
                .clock(clock)
                .build();
        Assert.assertEquals("warmup 应在 build 时完成加载", "v1", warmed.peek());
        warmed.close();

        RuntimeException boom = new RuntimeException("warmup-boom");
        try {
            RefreshableValue.<String>builder()
                    .name("warmup-fail")
                    .loader(ctx -> {
                        throw boom;
                    })
                    .warmup()
                    .clock(clock)
                    .build();
            Assert.fail("warmup 失败应从 build() 抛出异常");
        } catch (IllegalStateException e) {
            Assert.assertSame(boom, e.getCause());
        }
    }

    // ------------------------------------------------------------ 工具方法

    private static RefreshableValue.Builder<String> base(String name) {
        return RefreshableValue.<String>builder().name(name).loader(ctx -> "v");
    }

    private static void expectIllegal(RefreshableValue.Builder<String> builder, String messagePart) {
        try {
            builder.build();
            Assert.fail("应抛出 IllegalArgumentException，期望消息包含: " + messagePart);
        } catch (IllegalArgumentException e) {
            Assert.assertTrue("异常消息应包含 '" + messagePart + "'，实际: " + e.getMessage(),
                    e.getMessage() != null && e.getMessage().contains(messagePart));
        }
    }

    private static void awaitUntil(long timeoutMillis, String message, BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        Assert.fail(message);
    }
}
