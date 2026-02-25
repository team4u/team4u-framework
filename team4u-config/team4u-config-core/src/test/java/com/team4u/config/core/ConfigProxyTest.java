package com.team4u.config.core;

import com.team4u.config.core.domain.ConfigEntry;
import com.team4u.config.core.domain.ConfigSnapshot;
import com.team4u.config.core.proxy.ConfigProxyFactory;
import com.team4u.config.core.proxy.SnapshotAware;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class ConfigProxyTest {

    @Test
    public void testProxyBinding() {
        Map<String, ConfigEntry> entries = new HashMap<>();
        long now = System.currentTimeMillis();
        entries.put("app.name", new ConfigEntry("app.name", "test-app", "mock", now));
        entries.put("app.max-db-connections", new ConfigEntry("app.max-db-connections", "50", "mock", now));
        entries.put("app.dev-mode", new ConfigEntry("app.dev-mode", "true", "mock", now));

        ConfigSnapshot snapshot = new ConfigSnapshot(100L, entries);

        // 模拟管理器行为映射
        ConfigManager manager = new ConfigManager() {
            @Override
            public ConfigSnapshot currentSnapshot() {
                return snapshot;
            }

            @Override
            public <T> T createProxy(String prefix, Class<T> interfaceType) {
                return new ConfigProxyFactory().createLiveProxy(this, prefix, interfaceType);
            }

            @Override
            public void addChangeListener(String keyPattern, ConfigChangeListener listener) {
            }
        };

        AppConfig config = manager.createProxy("app.", AppConfig.class);

        Assert.assertEquals("test-app", config.name());
        Assert.assertEquals(50, config.maxDbConnections());
        Assert.assertTrue(config.isDevMode());

        // 测试快照锚定功能 (Pinning)
        AppConfig pinnedConfig = SnapshotAware.pin(config);
        Assert.assertNotNull(pinnedConfig);
        Assert.assertEquals(50, pinnedConfig.maxDbConnections());

        // 再次锚定应当返回自身对象实例
        Assert.assertSame(pinnedConfig, SnapshotAware.pin(pinnedConfig));
    }

    @Test
    public void testL2CacheEviction() {
        // 第一版数据
        Map<String, ConfigEntry> entriesV1 = new HashMap<>();
        long now = System.currentTimeMillis();
        entriesV1.put("app.name", new ConfigEntry("app.name", "v1-app", "mock", now));
        ConfigSnapshot snapshotV1 = new ConfigSnapshot(1L, entriesV1);

        // 第二版数据
        Map<String, ConfigEntry> entriesV2 = new HashMap<>();
        entriesV2.put("app.name", new ConfigEntry("app.name", "v2-app", "mock", now + 100));
        ConfigSnapshot snapshotV2 = new ConfigSnapshot(2L, entriesV2);

        AtomicReference<ConfigSnapshot> currentRef = new AtomicReference<>(snapshotV1);

        ConfigManager manager = new ConfigManager() {
            @Override
            public ConfigSnapshot currentSnapshot() {
                return currentRef.get();
            }

            @Override
            public <T> T createProxy(String prefix, Class<T> interfaceType) {
                return new ConfigProxyFactory().createLiveProxy(this, prefix, interfaceType);
            }

            @Override
            public void addChangeListener(String keyPattern, ConfigChangeListener listener) {
            }
        };

        AppConfig config = manager.createProxy("app.", AppConfig.class);

        // 首次调用触发转换逻辑并缓存结果
        Assert.assertEquals("v1-app", config.name());

        // 更新快照引用 (模拟重载管理器的原子原子替换动作)
        currentRef.set(snapshotV2);

        // 第二次调用应检测到版本变化，触发二级缓存失效并拿取最新值
        Assert.assertEquals("v2-app", config.name());
    }

    @Test
    public void testNamingCompatibility() {
        Map<String, ConfigEntry> entries = new HashMap<>();
        long now = System.currentTimeMillis();
        // 分别测试不同的命名风格
        entries.put("app.name", new ConfigEntry("app.name", "camel", "mock", now));
        entries.put("app.max-db-connections", new ConfigEntry("app.max-db-connections", "10", "mock", now));
        entries.put("app.server_port", new ConfigEntry("app.server_port", "8080", "mock", now));
        entries.put("app.dev.mode", new ConfigEntry("app.dev.mode", "true", "mock", now));

        ConfigSnapshot snapshot = new ConfigSnapshot(1L, entries);
        ConfigManager manager = new ConfigManager() {
            @Override
            public ConfigSnapshot currentSnapshot() {
                return snapshot;
            }

            @Override
            public <T> T createProxy(String prefix, Class<T> type) {
                return new ConfigProxyFactory().createLiveProxy(this, prefix, type);
            }

            @Override
            public void addChangeListener(String key, ConfigChangeListener l) {
            }
        };

        ComplexAppConfig config = manager.createProxy("app.", ComplexAppConfig.class);

        Assert.assertEquals("camel", config.name());
        Assert.assertEquals(10, config.maxDbConnections());
        Assert.assertEquals(8080, config.serverPort());
        Assert.assertTrue(config.isDevMode());
    }

    public interface AppConfig {
        String name();

        int maxDbConnections();

        boolean isDevMode();
    }

    public interface ComplexAppConfig {
        String name();               // 匹配 app.name

        int maxDbConnections();      // 匹配 app.max-db-connections (kebab)

        int serverPort();           // 匹配 app.server_port (snake)

        boolean isDevMode();         // 匹配 app.is.dev.mode (dot)
    }
}
