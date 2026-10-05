package com.team4u.framework.config.core.support;

import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.spi.InMemoryConfigSource;
import com.team4u.framework.config.core.spi.InstanceResolutionStrategy;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 自定义策略接入 ConfigDrivenRegistry 单元测试
 */
public class ConfigDrivenRegistryForStrategyTest {

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
    public void testCustomStrategyLazyLoadAndHotReload() {
        AtomicInteger buildCount = new AtomicInteger(0);
        AtomicInteger closeCount = new AtomicInteger(0);

        CustomPrefixStrategy<CustomClient> strategy = new CustomPrefixStrategy<>(
                "rpc://service/",
                (id, raw) -> {
                    buildCount.incrementAndGet();
                    return new CustomClient(id, raw, closeCount);
                }
        );

        ConfigDrivenRegistry<CustomClient> registry = ConfigDrivenRegistry.forStrategy(
                configManager, "rpc://service/*", strategy);

        // 验证初始未配置时，惰性加载返回 null（符合 null 契约且不入缓存）
        Assert.assertNull(registry.get("order"));
        Assert.assertEquals(0, buildCount.get());

        // 写入初始配置
        configSource.putAndRefresh("rpc://service/order", "timeout=3000");

        // 验证惰性加载成功
        CustomClient client1 = registry.get("order");
        Assert.assertNotNull(client1);
        Assert.assertEquals("order", client1.id);
        Assert.assertEquals("timeout=3000", client1.rawConfig);
        Assert.assertEquals(1, buildCount.get());

        // 重复读取命中缓存
        Assert.assertSame(client1, registry.get("order"));
        Assert.assertEquals(1, buildCount.get());

        // 更新配置 -> 触发安全热重载并释放旧实例资源
        configSource.putAndRefresh("rpc://service/order", "timeout=5000");
        CustomClient client2 = registry.get("order");
        Assert.assertNotNull(client2);
        Assert.assertNotSame(client1, client2);
        Assert.assertEquals("timeout=5000", client2.rawConfig);
        Assert.assertEquals(2, buildCount.get());
        Assert.assertEquals(1, closeCount.get());

        // 删除配置 -> 实例下线并释放资源
        configSource.putAndRefresh("rpc://service/order", null);
        Assert.assertNull(registry.get("order"));
        Assert.assertEquals(2, closeCount.get());
    }

    @Test
    public void testCustomStrategyWithDefaultInstanceKey() {
        CustomSingleStrategy<CustomClient> strategy = new CustomSingleStrategy<>(
                "app.global.endpoint",
                (key, raw) -> new CustomClient(key, raw, null)
        );

        ConfigDrivenRegistry<CustomClient> registry = ConfigDrivenRegistry.forStrategy(
                configManager, strategy);

        Assert.assertTrue(registry.defaultInstanceKey().isPresent());
        Assert.assertEquals("app.global.endpoint", registry.defaultInstanceKey().get());

        // 写入配置后，支持无参 get()
        configSource.putAndRefresh("app.global.endpoint", "https://api.example.com");
        CustomClient client = registry.get();
        Assert.assertNotNull(client);
        Assert.assertEquals("https://api.example.com", client.rawConfig);
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testCustomStrategyWithoutDefaultInstanceKeyThrowsExceptionOnNoArgGet() {
        CustomPrefixStrategy<CustomClient> strategy = new CustomPrefixStrategy<>(
                "rpc://service/",
                (id, raw) -> new CustomClient(id, raw, null)
        );

        ConfigDrivenRegistry<CustomClient> registry = ConfigDrivenRegistry.forStrategy(
                configManager, "rpc://service/*", strategy);

        Assert.assertFalse(registry.defaultInstanceKey().isPresent());
        registry.get();
    }

    @Test
    public void testCustomStrategyDestroy() {
        AtomicBoolean strategyDestroyed = new AtomicBoolean(false);
        AtomicInteger closeCount = new AtomicInteger(0);

        CustomPrefixStrategy<CustomClient> strategy = new CustomPrefixStrategy<CustomClient>(
                "rpc://service/",
                (id, raw) -> new CustomClient(id, raw, closeCount)
        ) {
            @Override
            public void destroy() {
                super.destroy();
                strategyDestroyed.set(true);
            }
        };

        ConfigDrivenRegistry<CustomClient> registry = ConfigDrivenRegistry.forStrategy(
                configManager, "rpc://service/*", strategy);

        configSource.putAndRefresh("rpc://service/payment", "timeout=2000");
        Assert.assertNotNull(registry.get("payment"));

        registry.destroy();
        Assert.assertEquals(1, closeCount.get());
        Assert.assertTrue(strategyDestroyed.get());

        // 销毁后变更不再生效
        configSource.putAndRefresh("rpc://service/payment", "timeout=4000");
        Assert.assertEquals(1, closeCount.get());
    }

    /**
     * 自定义前缀规则策略实现
     */
    private static class CustomPrefixStrategy<T> implements InstanceResolutionStrategy<T> {
        private final String uriPrefix;
        private final StrategyClientFactory<T> factory;

        CustomPrefixStrategy(String uriPrefix, StrategyClientFactory<T> factory) {
            this.uriPrefix = uriPrefix;
            this.factory = factory;
        }

        @Override
        public String resolveInstanceKey(String configKey) {
            if (configKey.startsWith(uriPrefix)) {
                return configKey.substring(uriPrefix.length());
            }
            return configKey;
        }

        @Override
        public T createInstance(ConfigManager configManager, String instanceKey) {
            String fullKey = uriPrefix + instanceKey;
            String raw = configManager.getString(fullKey).orElse(null);
            if (raw == null || raw.trim().isEmpty()) {
                return null;
            }
            return factory.create(instanceKey, raw);
        }

        @Override
        public void onConfigChanged(ConfigDrivenRegistry<T> registry, String changedKey, String oldValue, String newValue) {
            if (!changedKey.startsWith(uriPrefix)) {
                return;
            }
            String instanceKey = resolveInstanceKey(changedKey);
            if (newValue == null || newValue.trim().isEmpty()) {
                registry.removeAndClose(instanceKey);
                return;
            }
            registry.safeSwap(instanceKey, () -> factory.create(instanceKey, newValue));
        }
    }

    /**
     * 自定义单实例规则策略实现
     */
    private static class CustomSingleStrategy<T> implements InstanceResolutionStrategy<T> {
        private final String exactKey;
        private final StrategyClientFactory<T> factory;

        CustomSingleStrategy(String exactKey, StrategyClientFactory<T> factory) {
            this.exactKey = exactKey;
            this.factory = factory;
        }

        @Override
        public Optional<String> defaultInstanceKey() {
            return Optional.of(exactKey);
        }

        @Override
        public String resolveInstanceKey(String configKey) {
            return exactKey;
        }

        @Override
        public T createInstance(ConfigManager configManager, String instanceKey) {
            String raw = configManager.getString(exactKey).orElse(null);
            if (raw == null || raw.trim().isEmpty()) {
                return null;
            }
            return factory.create(exactKey, raw);
        }

        @Override
        public void onConfigChanged(ConfigDrivenRegistry<T> registry, String changedKey, String oldValue, String newValue) {
            if (!exactKey.equals(changedKey)) {
                return;
            }
            if (newValue == null || newValue.trim().isEmpty()) {
                registry.removeAndClose(exactKey);
                return;
            }
            registry.safeSwap(exactKey, () -> factory.create(exactKey, newValue));
        }
    }

    @FunctionalInterface
    private interface StrategyClientFactory<T> {
        T create(String key, String rawConfig);
    }

    private static class CustomClient implements AutoCloseable {
        private final String id;
        private final String rawConfig;
        private final AtomicInteger closeCount;

        CustomClient(String id, String rawConfig, AtomicInteger closeCount) {
            this.id = id;
            this.rawConfig = rawConfig;
            this.closeCount = closeCount;
        }

        @Override
        public void close() {
            if (closeCount != null) {
                closeCount.incrementAndGet();
            }
        }
    }
}
