package com.team4u.framework.base.util.cache;

import com.team4u.framework.base.util.ThreadUtil;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TimedCache 单元测试
 *
 * @author jay.wu
 */
public class TimedCacheTest {

    @Test
    public void testExpiration() {
        // 创建超时时间为 10ms 的缓存
        TimedCache<String, String> cache = new TimedCache<>(10);

        cache.put("k1", "v1");
        Assert.assertEquals("过期前应能获取值", "v1", cache.get("k1"));

        // 等待过期
        ThreadUtil.sleep(20);

        Assert.assertNull("过期后不应获取到值", cache.get("k1"));
        Assert.assertEquals("过期后 size 应只统计存活项", 0, cache.size());
    }

    @Test
    public void testNoExpiration() {
        // 设置为永不过期
        TimedCache<String, String> cache = new TimedCache<>(0);
        cache.put("k1", "v1");

        ThreadUtil.sleep(20);
        Assert.assertEquals("永不过期缓存应仍能获取值", "v1", cache.get("k1"));
    }

    @Test
    public void testBasicOps() {
        TimedCache<String, String> cache = new TimedCache<>(1000);
        cache.put("k1", "v1");
        Assert.assertEquals("读取值不正确", "v1", cache.get("k1"));

        cache.remove("k1");
        Assert.assertNull("移除后不应存在", cache.get("k1"));

        cache.put("k2", "v2");
        cache.clear();
        Assert.assertEquals("清空后大小应为 0", 0, cache.size());
    }

    @Test
    public void testSizeSkipsExpiredEntriesWithoutGet() {
        TimedCache<String, String> cache = new TimedCache<>(10);
        cache.put("k1", "v1");

        ThreadUtil.sleep(20);

        Assert.assertEquals(0, cache.size());
    }

    @Test
    public void testGetOrCreateReusesCachedValue() {
        TimedCache<String, String> cache = new TimedCache<>(1000);
        AtomicInteger createCount = new AtomicInteger();

        String value1 = cache.getOrCreate("k1", () -> {
            createCount.incrementAndGet();
            return "v1";
        });
        String value2 = cache.getOrCreate("k1", () -> {
            createCount.incrementAndGet();
            return "v2";
        });

        Assert.assertEquals("v1", value1);
        Assert.assertEquals("v1", value2);
        Assert.assertEquals(1, createCount.get());
    }

    @Test
    public void testGetOrCreateCreatesOnlyOnceUnderConcurrency() throws Exception {
        TimedCache<String, String> cache = new TimedCache<>(1000);
        AtomicInteger createCount = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(8);

        for (int i = 0; i < 8; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    Assert.assertEquals("v1", cache.getOrCreate("k1", () -> {
                        createCount.incrementAndGet();
                        ThreadUtil.sleep(10);
                        return "v1";
                    }));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Assert.fail("线程被意外中断");
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        Assert.assertTrue(done.await(2, TimeUnit.SECONDS));
        executor.shutdown();

        Assert.assertEquals(1, createCount.get());
    }
}
