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

        // 测试接口代理缓存
        TestInterface proxy1 = manager.createProxy("app", TestInterface.class);
        TestInterface proxy2 = manager.createProxy("app", TestInterface.class);
        Assert.assertSame("多次调用 createProxy 创建接口代理应返回同一实例", proxy1, proxy2);

        // 测试不同前缀
        TestInterface proxy3 = manager.createProxy("other", TestInterface.class);
        Assert.assertNotSame("不同前缀应返回不同代理实例", proxy1, proxy3);

        // 测试 Bean 绑定缓存
        TestBean result1 = manager.createProxy("bean", TestBean.class);
        TestBean result2 = manager.createProxy("bean", TestBean.class);

        Assert.assertSame("多次调用 createProxy 绑定对象应返回同一实例", result1, result2);
        Assert.assertEquals("ConfigBinder.bind 应只被调用一次", 1, bindCount.get());
    }

    public interface TestInterface {
        String name();
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
