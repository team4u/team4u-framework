package com.team4u.framework.config.core.internal;

import com.team4u.framework.config.core.ConfigProxyCreator;
import com.team4u.framework.config.core.TestConfigProxyCreator;
import com.team4u.framework.config.core.convert.PropertyConverterRegistry;
import com.team4u.framework.config.core.proxy.SnapshotAware;
import com.team4u.framework.config.core.spi.*;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DefaultConfigManager 缓存功能单元测试
 */
public class DefaultConfigManagerCacheTest {

    @Test
    public void testProxyCaching() {
        ConfigSourceRegistry sourceRegistry = new ConfigSourceRegistry() {
            @Override
            public List<ConfigSource> getPolicies() {
                return Collections.emptyList();
            }
        };
        ConfigWatcherRegistry watcherRegistry = new ConfigWatcherRegistry() {
            @Override
            public List<ConfigWatcher> getPolicies() {
                return Collections.emptyList();
            }
        };

        ConfigProxyCreator creator = new TestConfigProxyCreator();

        DefaultConfigManager manager = new DefaultConfigManager(sourceRegistry, watcherRegistry,
                new PropertyConverterRegistry(), creator, 500);

        // 测试 Bean 代理缓存
        TestConfig proxy1 = manager.createProxy("app", TestConfig.class);
        TestConfig proxy2 = manager.createProxy("app", TestConfig.class);
        Assert.assertSame("多次调用 createProxy 应返回同一代理实例", proxy1, proxy2);

        // 测试不同前缀
        TestConfig proxy3 = manager.createProxy("other", TestConfig.class);
        Assert.assertNotSame("不同前缀应返回不同代理实例", proxy1, proxy3);
    }

    public static class TestConfig {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    public static class TestBean {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
