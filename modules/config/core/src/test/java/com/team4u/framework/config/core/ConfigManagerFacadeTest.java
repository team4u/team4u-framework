package com.team4u.framework.config.core;

import com.team4u.framework.config.core.spi.ConfigWatcher;
import com.team4u.framework.config.core.spi.InMemoryConfigSource;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class ConfigManagerFacadeTest {

    @Test
    public void testStandardSingleton() {
        ConfigManager manager1 = ConfigManager.global();
        ConfigManager manager2 = ConfigManager.global();
        Assert.assertNotNull(manager1);
        Assert.assertSame(manager1, manager2);
    }

    @Test
    public void testBuilder() {
        InMemoryConfigSource source = new InMemoryConfigSource("MockSource", 100);
        source.put("test.key", "test-value");

        ConfigManager manager = ConfigManager.builder()
                .addSource(source)
                .build();

        Assert.assertNotNull(manager);

        // 验证数据被成功读取
        Assert.assertEquals("test-value", manager.getString("test.key").orElse(null));
    }

    private static class MockWatcher implements ConfigWatcher {
        @Override
        public void watch(Runnable changeSignal) {
        }
    }
}
