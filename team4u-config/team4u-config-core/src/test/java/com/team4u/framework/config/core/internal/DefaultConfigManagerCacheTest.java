package com.team4u.framework.config.core.internal;

import com.team4u.framework.config.core.convert.PropertyConverterRegistry;
import com.team4u.framework.config.core.domain.ConfigSnapshot;
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

        AtomicInteger bindCount = new AtomicInteger(0);
        TestBean testBean = new TestBean();

        ConfigBinder configBinder = new ConfigBinder() {
            @Override
            public <T> T bind(ConfigSnapshot snapshot, String prefix, Class<T> type) {
                bindCount.incrementAndGet();
                if (type == TestBean.class && "bean".equals(prefix)) {
                    return (T) testBean;
                }
                return null;
            }
        };

        DefaultConfigManager manager = new DefaultConfigManager(sourceRegistry, watcherRegistry,
                new PropertyConverterRegistry(), configBinder);

        // 测试 Bean 代理缓存
        TestConfig proxy1 = manager.createProxy("app", TestConfig.class);
        TestConfig proxy2 = manager.createProxy("app", TestConfig.class);
        Assert.assertSame("多次调用 createProxy 应返回同一代理实例", proxy1, proxy2);

        // 测试不同前缀
        TestConfig proxy3 = manager.createProxy("other", TestConfig.class);
        Assert.assertNotSame("不同前缀应返回不同代理实例", proxy1, proxy3);

        // 测试 Bean 绑定缓存
        TestBean result1 = manager.createProxy("bean", TestBean.class);
        TestBean result2 = manager.createProxy("bean", TestBean.class);

        Assert.assertSame("多次调用 createProxy 绑定对象应返回同一实例", result1, result2);
        // 由于现在默认使用代理，ConfigBinder 不再被调用
        Assert.assertEquals("由于使用了代理，ConfigBinder.bind 不应被调用", 0, bindCount.get());
        Assert.assertTrue("结果应该是代理对象", result1 instanceof com.team4u.framework.config.core.proxy.SnapshotAware);
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
