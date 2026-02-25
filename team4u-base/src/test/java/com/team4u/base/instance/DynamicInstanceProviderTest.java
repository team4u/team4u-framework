package com.team4u.base.instance;

import cn.hutool.cache.CacheUtil;
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
                (id, config) -> {
                    createCount.incrementAndGet();
                    return new InstanceMock(config.getValue());
                });

        String configContent = "{\"key\": \"val\"}";

        // 1. 第一次获取：解析并创建
        InstanceMock p1 = provider.get("t1", configContent);
        Assert.assertNotNull(p1);
        Assert.assertEquals("{\"key\": \"val\"}", p1.getValue());
        Assert.assertEquals(1, parseCount.get());
        Assert.assertEquals(1, createCount.get());

        // 2. 第二次获取相同标识：通过 hash 快速命中缓存，跳过解析
        InstanceMock p2 = provider.get("t1", configContent);
        Assert.assertSame(p1, p2);
        Assert.assertEquals(1, parseCount.get()); // hash 命中，跳过解析
        Assert.assertEquals(1, createCount.get()); // 实例不应重复创建

        // 3. 配置变更获取：检测到变更，重新创建
        String newConfigContent = "{\"key\": \"new_val\"}";
        InstanceMock p3 = provider.get("t1", newConfigContent);
        Assert.assertNotSame(p1, p3);
        Assert.assertEquals("{\"key\": \"new_val\"}", p3.getValue());
        Assert.assertEquals(2, parseCount.get());
        Assert.assertEquals(2, createCount.get());

        // 4. 手动失效后再获取
        provider.invalidate("t1");
        InstanceMock p4 = provider.get("t1", newConfigContent);
        Assert.assertNotSame(p3, p4);
        Assert.assertEquals(3, parseCount.get());
        Assert.assertEquals(3, createCount.get());

        // 5. 清理测试
        provider.clear();
        Assert.assertEquals(0, provider.size());
    }

    /**
     * 测试 hash 快速路径优化：相同 input 重复调用时跳过解析
     */
    @Test
    public void testHashFastPathSkipParsing() {
        AtomicInteger parseCount = new AtomicInteger();
        AtomicInteger createCount = new AtomicInteger();

        DynamicInstanceProvider<String, ConfigMock, InstanceMock> provider = DynamicInstanceProvider.createStringLru(
                100,
                rawConfig -> {
                    parseCount.incrementAndGet();
                    return new ConfigMock(rawConfig);
                },
                (id, config) -> {
                    createCount.incrementAndGet();
                    return new InstanceMock(config.getValue());
                });

        String configContent = "test-config";

        // 首次调用：需要解析
        provider.get("id1", configContent);
        Assert.assertEquals(1, parseCount.get());
        Assert.assertEquals(1, createCount.get());

        // 后续 10 次相同调用：全部跳过解析
        for (int i = 0; i < 10; i++) {
            provider.get("id1", configContent);
        }
        Assert.assertEquals(1, parseCount.get()); // 仍然只解析了 1 次
        Assert.assertEquals(1, createCount.get()); // 实例只创建 1 次
    }

    /**
     * 测试 hash 变化时触发解析
     */
    @Test
    public void testHashChangeTriggersParsing() {
        AtomicInteger parseCount = new AtomicInteger();

        DynamicInstanceProvider<String, ConfigMock, InstanceMock> provider = DynamicInstanceProvider.createStringLru(
                100,
                rawConfig -> {
                    parseCount.incrementAndGet();
                    return new ConfigMock(rawConfig);
                },
                (id, config) -> new InstanceMock(config.getValue()));

        provider.get("id1", "config-v1");
        Assert.assertEquals(1, parseCount.get());

        provider.get("id1", "config-v2"); // hash 变化，触发解析
        Assert.assertEquals(2, parseCount.get());

        provider.get("id1", "config-v2"); // hash 未变，跳过解析
        Assert.assertEquals(2, parseCount.get());
    }

    @Test
    public void testMapInput() {
        // 输入源为 Map
        DynamicInstanceProvider<Map<String, Object>, ConfigMock, InstanceMock> provider = new DynamicInstanceProvider<>(
                CacheUtil.newLRUCache(100),
                map -> new ConfigMock((String) map.get("value")),
                (id, config) -> new InstanceMock(config.getValue()));

        Map<String, Object> input = new HashMap<>();
        input.put("value", "v1");

        InstanceMock p1 = provider.get("id1", input);
        Assert.assertEquals("v1", p1.getValue());

        // 配置变更
        Map<String, Object> input2 = new HashMap<>();
        input2.put("value", "v2");
        InstanceMock p2 = provider.get("id1", input2);
        Assert.assertEquals("v2", p2.getValue());
        Assert.assertNotSame(p1, p2);
    }

    @Test
    public void testLruEviction() {
        DynamicInstanceProvider<String, ConfigMock, InstanceMock> provider = DynamicInstanceProvider.createStringLru(
                2,
                ConfigMock::new,
                (id, config) -> new InstanceMock(config.getValue()));

        provider.get("p1", "v1");
        provider.get("p2", "v2");
        Assert.assertEquals(2, provider.size());

        // p1 变成最近最少使用
        provider.get("p2", "v2");

        // 增加 p3，导致 p1 被淘汰
        provider.get("p3", "v3");
        Assert.assertEquals(2, provider.size());
    }

    /**
     * 测试 LRU 淘汰时 hash 缓存同步淘汰
     */
    @Test
    public void testHashCacheEviction() {
        AtomicInteger parseCount = new AtomicInteger();

        DynamicInstanceProvider<String, ConfigMock, InstanceMock> provider = DynamicInstanceProvider.createStringLru(
                2,
                rawConfig -> {
                    parseCount.incrementAndGet();
                    return new ConfigMock(rawConfig);
                },
                (id, config) -> new InstanceMock(config.getValue()));

        // 填满缓存
        provider.get("p1", "v1");
        provider.get("p2", "v2");
        Assert.assertEquals(2, provider.size());
        Assert.assertEquals(2, parseCount.get());

        // 访问 p2，使 p1 成为 LRU
        provider.get("p2", "v2");
        Assert.assertEquals(2, parseCount.get()); // hash 命中，跳过解析

        // 添加 p3，p1 被淘汰（包括其 hash 缓存）
        provider.get("p3", "v3");
        Assert.assertEquals(3, parseCount.get());

        // 再次访问 p1，由于已被淘汰，需要重新解析
        provider.get("p1", "v1");
        Assert.assertEquals(4, parseCount.get()); // 重新解析
    }

    /**
     * 测试手动失效时 hash 缓存同步清理
     */
    @Test
    public void testInvalidateClearsHashCache() {
        AtomicInteger parseCount = new AtomicInteger();

        DynamicInstanceProvider<String, ConfigMock, InstanceMock> provider = DynamicInstanceProvider.createStringLru(
                100,
                rawConfig -> {
                    parseCount.incrementAndGet();
                    return new ConfigMock(rawConfig);
                },
                (id, config) -> new InstanceMock(config.getValue()));

        provider.get("id1", "config1");
        Assert.assertEquals(1, parseCount.get());

        // 相同配置再次调用，跳过解析
        provider.get("id1", "config1");
        Assert.assertEquals(1, parseCount.get());

        // 手动失效
        provider.invalidate("id1");

        // 再次调用，需要重新解析（hash 缓存已清理）
        provider.get("id1", "config1");
        Assert.assertEquals(2, parseCount.get());
    }

    /**
     * 测试 clear() 同时清理 hash 缓存
     */
    @Test
    public void testClearClearsHashCache() {
        AtomicInteger parseCount = new AtomicInteger();

        DynamicInstanceProvider<String, ConfigMock, InstanceMock> provider = DynamicInstanceProvider.createStringLru(
                100,
                rawConfig -> {
                    parseCount.incrementAndGet();
                    return new ConfigMock(rawConfig);
                },
                (id, config) -> new InstanceMock(config.getValue()));

        provider.get("id1", "config1");
        Assert.assertEquals(1, parseCount.get());

        // 清空缓存
        provider.clear();

        // 再次调用，需要重新解析
        provider.get("id1", "config1");
        Assert.assertEquals(2, parseCount.get());
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
                (id, config) -> {
                    createCount.incrementAndGet();
                    return new InstanceMock(config.getValue());
                });

        int threadCount = 10;
        int iterationsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        // 多线程并发访问同一个 configId
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < iterationsPerThread; j++) {
                        InstanceMock policy = provider.get("shared-id", "shared-config");
                        Assert.assertNotNull(policy);
                        Assert.assertEquals("shared-config", policy.getValue());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // 验证只解析和创建了一次（hash 快速路径 + 双重检查锁）
        Assert.assertEquals(1, parseCount.get());
        Assert.assertEquals(1, createCount.get());
    }

    /**
     * 测试不同 configId 的并发访问
     */
    @Test
    public void testConcurrentDifferentConfigIds() throws InterruptedException {
        AtomicInteger createCount = new AtomicInteger();

        DynamicInstanceProvider<String, ConfigMock, InstanceMock> provider = DynamicInstanceProvider.createStringLru(
                100,
                ConfigMock::new,
                (id, config) -> {
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
                    String configId = "config-" + threadId;
                    String config = "value-" + threadId;
                    InstanceMock policy = provider.get(configId, config);
                    Assert.assertNotNull(policy);
                    Assert.assertEquals(config, policy.getValue());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // 每个 configId 应该只创建一次实例
        Assert.assertEquals(threadCount, createCount.get());
    }

    @Test
    public void testProvideBlankConfig() {
        DynamicInstanceProvider<String, ConfigMock, InstanceMock> provider = DynamicInstanceProvider.createStringLru(
                100,
                ConfigMock::new,
                (id, config) -> new InstanceMock(config.getValue()));

        Assert.assertNull(provider.get("test", null));
        Assert.assertNull(provider.get("test", "   "));
    }

    /**
     * 测试简化版 get(input) 方法
     */
    @Test
    public void testSimplifiedGet() {
        AtomicInteger createCount = new AtomicInteger();
        DynamicInstanceProvider<String, ConfigMock, InstanceMock> provider = DynamicInstanceProvider.createStringLru(
                100,
                ConfigMock::new,
                (id, config) -> {
                    createCount.incrementAndGet();
                    return new InstanceMock(config.getValue());
                });

        String config = "v1";
        // 1. 第一次获取
        InstanceMock p1 = provider.get(config);
        Assert.assertNotNull(p1);
        Assert.assertEquals(1, createCount.get());

        // 2. 相同内容重复获取，应命中缓存
        InstanceMock p2 = provider.get(config);
        Assert.assertSame(p1, p2);
        Assert.assertEquals(1, createCount.get());

        // 3. 不同内容获取
        provider.get("v2");
        Assert.assertEquals(2, createCount.get());
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
                (id, config) -> {
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
