package com.team4u.framework.base.instance;

import com.team4u.framework.base.util.CacheUtil;
import lombok.Data;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 动态实例工厂相关的单元测试
 */
public class DynamicInstanceProviderTest {

    @Test
    public void testGet() {
        AtomicInteger parseCount = new AtomicInteger();
        AtomicInteger createCount = new AtomicInteger();

        // 明确指定输入源为 String
        DynamicInstanceProvider<String, ConfigMock, InstanceMock> provider = DynamicInstanceProvider.createStringLru(
                100,
                rawConfig -> {
                    parseCount.incrementAndGet();
                    return new ConfigMock(rawConfig);
                },
                config -> {
                    createCount.incrementAndGet();
                    return new InstanceMock(config.getValue());
                });

        String configContent = "{\"key\": \"val\"}";

        // 1. 第一次获取：解析并创建
        InstanceMock p1 = provider.get(configContent);
        Assert.assertNotNull(p1);
        Assert.assertEquals("{\"key\": \"val\"}", p1.getValue());
        Assert.assertEquals(1, parseCount.get());
        Assert.assertEquals(1, createCount.get());

        // 2. 第二次获取相同输入：快速命中缓存，跳过解析和创建
        InstanceMock p2 = provider.get(configContent);
        Assert.assertSame(p1, p2);
        Assert.assertEquals(1, parseCount.get());
        Assert.assertEquals(1, createCount.get());

        // 3. 配置变更获取：新的输入，重新解析和创建
        String newConfigContent = "{\"key\": \"new_val\"}";
        InstanceMock p3 = provider.get(newConfigContent);
        Assert.assertNotSame(p1, p3);
        Assert.assertEquals("{\"key\": \"new_val\"}", p3.getValue());
        Assert.assertEquals(2, parseCount.get());
        Assert.assertEquals(2, createCount.get());

        // 4. 手动失效后再获取
        provider.invalidate(newConfigContent);
        InstanceMock p4 = provider.get(newConfigContent);
        Assert.assertNotSame(p3, p4);
        Assert.assertEquals(3, parseCount.get());
        Assert.assertEquals(3, createCount.get());

        // 5. 清理测试
        provider.clear();
        Assert.assertEquals(0, provider.size());
    }

    @Test
    public void testMapInput() {
        // 输入源为 Map
        DynamicInstanceProvider<Map<String, Object>, ConfigMock, InstanceMock> provider = new DynamicInstanceProvider<>(
                CacheUtil.newLRUCache(100),
                map -> new ConfigMock((String) map.get("value")),
                config -> new InstanceMock(config.getValue()));

        Map<String, Object> input = new HashMap<>();
        input.put("value", "v1");

        InstanceMock p1 = provider.get(input);
        Assert.assertEquals("v1", p1.getValue());

        // 使用相同内容的 Map 进行第二次获取，由于 Map 的 equals 是按内容比较的，所以应该命中缓存
        Map<String, Object> inputSame = new HashMap<>();
        inputSame.put("value", "v1");
        InstanceMock p1b = provider.get(inputSame);
        Assert.assertSame(p1, p1b);

        // 配置变更
        Map<String, Object> input2 = new HashMap<>();
        input2.put("value", "v2");
        InstanceMock p2 = provider.get(input2);
        Assert.assertEquals("v2", p2.getValue());
        Assert.assertNotSame(p1, p2);
    }

    @Test
    public void testLruEviction() {
        DynamicInstanceProvider<String, ConfigMock, InstanceMock> provider = DynamicInstanceProvider.createStringLru(
                2,
                ConfigMock::new,
                config -> new InstanceMock(config.getValue()));

        provider.get("v1");
        provider.get("v2");
        Assert.assertEquals(2, provider.size());

        // v1 变成最近最少使用
        provider.get("v2");

        // 增加 v3，导致 v1 被淘汰
        provider.get("v3");
        Assert.assertEquals(2, provider.size());
    }

    /**
     * 测试并发场景下的正确性
     */
    @Test
    public void testConcurrentAccess() throws InterruptedException {
        AtomicInteger parseCount = new AtomicInteger();
        AtomicInteger createCount = new AtomicInteger();

        DynamicInstanceProvider<String, ConfigMock, InstanceMock> provider = DynamicInstanceProvider.createStringLru(
                100,
                rawConfig -> {
                    parseCount.incrementAndGet();
                    return new ConfigMock(rawConfig);
                },
                config -> {
                    createCount.incrementAndGet();
                    return new InstanceMock(config.getValue());
                });

        int threadCount = 10;
        int iterationsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        String sharedConfig = "shared-config";

        // 多线程并发访问同一个 config
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < iterationsPerThread; j++) {
                        InstanceMock policy = provider.get(sharedConfig);
                        Assert.assertNotNull(policy);
                        Assert.assertEquals(sharedConfig, policy.getValue());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // 验证只解析和创建了一次（双重检查锁）
        Assert.assertEquals(1, parseCount.get());
        Assert.assertEquals(1, createCount.get());
    }

    /**
     * 测试不同 config 的并发访问
     */
    @Test
    public void testConcurrentDifferentConfigs() throws InterruptedException {
        AtomicInteger createCount = new AtomicInteger();

        DynamicInstanceProvider<String, ConfigMock, InstanceMock> provider = DynamicInstanceProvider.createStringLru(
                100,
                ConfigMock::new,
                config -> {
                    createCount.incrementAndGet();
                    return new InstanceMock(config.getValue());
                });

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    String config = "value-" + threadId;
                    InstanceMock policy = provider.get(config);
                    Assert.assertNotNull(policy);
                    Assert.assertEquals(config, policy.getValue());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // 每个 config 应该只创建一次实例
        Assert.assertEquals(threadCount, createCount.get());
    }

    @Test
    public void testProvideBlankConfig() {
        DynamicInstanceProvider<String, ConfigMock, InstanceMock> provider = DynamicInstanceProvider.createStringLru(
                100,
                ConfigMock::new,
                config -> new InstanceMock(config.getValue()));

        Assert.assertNull(provider.get(null));
        Assert.assertNull(provider.get("   "));
    }

    /**
     * 测试简化版 getByConfig(config) 方法
     */
    @Test
    public void testSimplifiedGetByConfig() {
        AtomicInteger createCount = new AtomicInteger();
        DynamicInstanceProvider<String, ConfigMock, InstanceMock> provider = DynamicInstanceProvider.createStringLru(
                100,
                ConfigMock::new,
                config -> {
                    createCount.incrementAndGet();
                    return new InstanceMock(config.getValue());
                });

        ConfigMock config = new ConfigMock("c1");
        // 1. 第一次获取
        InstanceMock p1 = provider.getByConfig(config);
        Assert.assertNotNull(p1);
        Assert.assertEquals(1, createCount.get());

        // 2. 相同内容重复获取，应命中缓存
        InstanceMock p2 = provider.getByConfig(config);
        Assert.assertSame(p1, p2);
        Assert.assertEquals(1, createCount.get());
    }

    @Data
    static class ConfigMock {
        private final String value;

        public ConfigMock(String value) {
            this.value = value;
        }
    }

    @Data
    static class InstanceMock {
        private final String value;

        public InstanceMock(String value) {
            this.value = value;
        }
    }
}
