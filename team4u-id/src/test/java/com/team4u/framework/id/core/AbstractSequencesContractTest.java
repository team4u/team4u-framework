package com.team4u.framework.id.core;

import com.team4u.framework.config.test.TestConfigContext;
import com.team4u.framework.id.api.SeqConfigException;
import com.team4u.framework.id.api.SeqExhaustedException;
import com.team4u.framework.id.group.GroupKeyPolicies;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.KvStores;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.Clock;
import java.util.Collections;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/**
 * 序号服务行为契约测试基类
 * <p>
 * 任何 {@link KvStore} 后端（实现 {@code CounterCapable}，如 memory/jdbc/redis）
 * 继承本类并实现 {@link #createStore()} 与 {@link #advanceMillis(long)}，
 * 即可跑同一套行为契约，保证「取号、耗尽、循环、分组、号段、格式化」语义
 * 跨后端一致——对齐 team4u-kv-test 的契约测试惯例。
 * </p>
 * 虚拟时钟由实现提供并推进，用于验证按日期分组的周期重置。
 *
 * @author jay.wu
 */
public abstract class AbstractSequencesContractTest {

    protected TestConfigContext config;
    protected SequenceService service;
    protected KvStore store;

    /**
     * 创建被测存储（须实现 CounterCapable）
     */
    protected abstract KvStore createStore();

    /**
     * 服务时钟（与存储时钟一致，按日期分组测试依赖其可推进）
     */
    protected abstract Clock clock();

    /**
     * 推进虚拟时间（毫秒）
     */
    protected abstract void advanceMillis(long millis);

    @Before
    public void setUpService() {
        config = TestConfigContext.create();
        store = createStore();
        service = new SequenceService(config.getConfigManager(), store,
                SequenceService.DEFAULT_CONFIG_PATTERN, SequenceService.DEFAULT_SPACE,
                new GroupKeyPolicies().registry(), clock(), 1024);
    }

    @After
    public void tearDownService() {
        service.destroy();
        config.destroy();
        KvStores.closeQuietly(store);
    }

    /**
     * 写入一条规则
     */
    protected void rule(String name, String json) {
        config.put("seq." + name, json);
    }

    // ------------------------------------------------- 取号与耗尽

    @Test
    public void contractNextFromStartWithStep() {
        rule("order", "{\"start\":100,\"step\":5}");
        assertEquals(100, service.next("order"));
        assertEquals(105, service.next("order"));
        assertEquals(110, service.next("order"));
    }

    @Test
    public void contractDefaultRule() {
        rule("global", "{}");
        assertEquals(1, service.next("global"));
        assertEquals(2, service.next("global"));
        assertEquals(3, service.next("global"));
    }

    @Test
    public void contractTryNextExhausted() {
        rule("quota", "{\"maxValue\":3}");
        assertEquals(1L, service.tryNext("quota").longValue());
        assertEquals(2L, service.tryNext("quota").longValue());
        assertEquals(3L, service.tryNext("quota").longValue());
        assertNull("exhausted must return null", service.tryNext("quota"));
        assertNull(service.tryNext("quota"));
    }

    @Test
    public void contractNextThrowsWhenExhausted() {
        rule("quota", "{\"maxValue\":1}");
        assertEquals(1, service.next("quota"));
        try {
            service.next("quota");
            fail("expected SeqExhaustedException");
        } catch (SeqExhaustedException ignored) {
        }
    }

    @Test
    public void contractRecycleAfterMax() {
        rule("cyclic", "{\"maxValue\":5,\"recycle\":true}");
        for (int round = 0; round < 2; round++) {
            for (int i = 1; i <= 5; i++) {
                assertEquals("round=" + round + ",i=" + i, i, service.next("cyclic"));
            }
        }
    }

    @Test
    public void contractRecycleWithStep() {
        rule("cyclic", "{\"start\":10,\"step\":10,\"maxValue\":30,\"recycle\":true}");
        long[] expected = {10, 20, 30, 10, 20, 30};
        for (long value : expected) {
            assertEquals(value, service.next("cyclic"));
        }
    }

    @Test
    public void contractMissingRuleThrows() {
        try {
            service.next("notExist");
            fail("expected SeqConfigException");
        } catch (SeqConfigException ignored) {
        }
    }

    // ------------------------------------------------- 本地号段

    @Test
    public void contractSegmentMatchesDirect() {
        rule("direct", "{}");
        rule("segmented", "{\"segment\":10}");
        for (int i = 1; i <= 25; i++) {
            assertEquals("i=" + i, service.next("direct"), service.next("segmented"));
        }
    }

    @Test
    public void contractSegmentExhausted() {
        // 号段长度 3 不整除可用数 8：验证越界号段被正确拒绝
        rule("quota", "{\"maxValue\":8,\"segment\":3}");
        for (int i = 1; i <= 8; i++) {
            assertEquals(i, service.next("quota"));
        }
        assertNull(service.tryNext("quota"));
        assertNull(service.tryNext("quota"));
    }

    @Test
    public void contractSegmentRecycle() {
        rule("cyclic", "{\"maxValue\":5,\"segment\":2,\"recycle\":true}");
        for (int round = 0; round < 2; round++) {
            for (int i = 1; i <= 5; i++) {
                assertEquals("round=" + round + ",i=" + i, i, service.next("cyclic"));
            }
        }
    }

    // ------------------------------------------------- 分组

    @Test
    public void contractGroupByDateResetsNextDay() {
        rule("daily", "{\"group\":{}}");
        assertEquals(1, service.next("daily"));
        assertEquals(2, service.next("daily"));

        advanceMillis(24 * 3600_000L);
        assertEquals("group key must change next day", 1, service.next("daily"));
    }

    @Test
    public void contractGroupByMonthKeepsSameDay() {
        rule("monthly", "{\"group\":{\"format\":\"yyyyMM\"}}");
        assertEquals(1, service.next("monthly"));

        advanceMillis(24 * 3600_000L);
        assertEquals("same month must keep counting", 2, service.next("monthly"));

        advanceMillis(32L * 24 * 3600_000L);
        assertEquals("next month must reset", 1, service.next("monthly"));
    }

    @Test
    public void contractGroupByExt() {
        rule("merchant", "{\"group\":{\"type\":\"EXT\",\"extKey\":\"merchantId\"}}");
        Map<String, Object> a = Collections.singletonMap("merchantId", "M001");
        Map<String, Object> b = Collections.singletonMap("merchantId", "M002");

        assertEquals(1, service.next("merchant", a));
        assertEquals(1, service.next("merchant", b));
        assertEquals(2, service.next("merchant", a));
        assertEquals(2, service.next("merchant", b));
    }

    @Test
    public void contractGroupByExtMissingKeyThrows() {
        rule("merchant", "{\"group\":{\"type\":\"EXT\",\"extKey\":\"merchantId\"}}");
        try {
            service.next("merchant");
            fail("expected SeqConfigException");
        } catch (SeqConfigException ignored) {
        }
    }

    // ------------------------------------------------- 格式化

    @Test
    public void contractFormatted() {
        // 虚拟时钟从 epoch 0（1970-01-01）开始，分组标识可精确预期
        rule("orderNo", "{\"start\":42,\"seqLength\":6,\"format\":\"ORD-${group}-${seq}\"}");
        assertEquals("ORD--000042", service.nextFormatted("orderNo"));

        rule("orderNoGrouped",
                "{\"start\":42,\"seqLength\":6,\"format\":\"ORD-${group}-${seq}\",\"group\":{}}");
        assertEquals("ORD-19700101-000042", service.nextFormatted("orderNoGrouped"));
        assertEquals("ORD-19700101-000043", service.nextFormatted("orderNoGrouped"));
    }

    @Test
    public void contractFormattedWithoutTemplate() {
        rule("padded", "{\"seqLength\":4}");
        assertEquals("0001", service.nextFormatted("padded"));
    }

    // ------------------------------------------------- 并发

    @Test
    public void concurrentSegmentNoDuplicates() throws Exception {
        rule("hot", "{\"segment\":100}");
        int threads = 4;
        int perThread = 500;
        Set<Long> issued = ConcurrentHashMap.newKeySet();
        AtomicLong exhausted = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    start.await();
                    for (int j = 0; j < perThread; j++) {
                        Long value = service.tryNext("hot");
                        if (value == null) {
                            exhausted.incrementAndGet();
                        } else {
                            issued.add(value);
                        }
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrueAwait(pool);
        } finally {
            pool.shutdownNow();
        }

        assertEquals("no exhausted expected", 0, exhausted.get());
        assertEquals("no duplicate numbers allowed", threads * perThread, issued.size());
        LongSummaryStatistics stats = issued.stream().mapToLong(Long::longValue).summaryStatistics();
        assertEquals(1, stats.getMin());
        assertEquals(threads * perThread, stats.getMax());
    }

    private static void assertTrueAwait(ExecutorService pool) throws InterruptedException {
        if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
            throw new IllegalStateException("pool not terminated in 60s");
        }
    }
}
