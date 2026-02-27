package com.team4u.framework.config.core.support;

import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.spi.InMemoryConfigSource;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * ConfigDrivenRegistry 单元测试
 */
public class ConfigDrivenRegistryTest {

    private InMemoryConfigSource configSource;
    private ConfigManager configManager;

    @Before
    public void setUp() {
        configSource = new InMemoryConfigSource("test", 100);
        configManager = ConfigManager.builder()
                .addSource(configSource)
                .addWatcher(configSource)
                .debounceWindow(0)
                .build();
    }

    @Test
    public void testLazyLoad() {
        ConfigDrivenRegistry<String> registry = new ConfigDrivenRegistry<>(
                configManager, "test.", String::toUpperCase);

        configSource.putAndRefresh("test.k1", "v1");

        // 验证延迟初始化逻辑
        Assert.assertEquals("V1", registry.get("test.k1"));
    }

    @Test
    public void testHotReload() {
        ConfigDrivenRegistry<String> registry = new ConfigDrivenRegistry<>(
                configManager, "test.", String::toUpperCase);

        configSource.putAndRefresh("test.k1", "v1");
        Assert.assertEquals("V1", registry.get("test.k1"));

        // 更新配置
        configSource.putAndRefresh("test.k1", "v2");
        // 验证自动刷新逻辑
        Assert.assertEquals("V2", registry.get("test.k1"));
    }

    @Test
    public void testSafeSwap() {
        ConfigDrivenRegistry<String> registry = new ConfigDrivenRegistry<>(
                configManager, "test.", val -> {
                    if ("error".equals(val)) {
                        throw new RuntimeException("Invalid config");
                    }
                    return val.toUpperCase();
                });

        configSource.putAndRefresh("test.k1", "v1");
        Assert.assertEquals("V1", registry.get("test.k1"));

        // 模拟配置异常
        configSource.putAndRefresh("test.k1", "error");
        // 异常时应保留历史版本实例
        Assert.assertEquals("V1", registry.get("test.k1"));

        // 恢复正常配置
        configSource.putAndRefresh("test.k1", "v2");
        Assert.assertEquals("V2", registry.get("test.k1"));
    }

    @Test
    public void testGracefulShutdown() throws Exception {
        AtomicInteger closeCount = new AtomicInteger(0);

        ConfigDrivenRegistry<MockInstance> registry = new ConfigDrivenRegistry<>(
                configManager, "test.", name -> new MockInstance(name, closeCount));

        configSource.putAndRefresh("test.k1", "i1");
        MockInstance i1 = registry.get("test.k1");
        Assert.assertEquals("i1", i1.toString());

        // 执行实例替换
        configSource.putAndRefresh("test.k1", "i2");
        Assert.assertEquals("i2", registry.get("test.k1").toString());

        // 验证历史实例资源已回收
        Assert.assertEquals(1, closeCount.get());

        // 执行配置删除
        configSource.putAndRefresh("test.k1", null);
        Assert.assertNull(registry.get("test.k1"));
        // 验证实例资源已回收
        Assert.assertEquals(2, closeCount.get());
    }

    private static class MockInstance implements AutoCloseable {
        private final String name;
        private final AtomicInteger closeCount;

        MockInstance(String name, AtomicInteger closeCount) {
            this.name = name;
            this.closeCount = closeCount;
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
