package com.team4u.framework.config.core;

import com.team4u.framework.config.core.convert.PropertyConverterRegistry;
import com.team4u.framework.config.core.domain.ConfigEntry;
import com.team4u.framework.config.core.domain.ConfigSnapshot;
import com.team4u.framework.config.core.proxy.ConfigProxyFactory;
import com.team4u.framework.config.core.proxy.SnapshotAware;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 配置代理核心功能单元测试
 */
public class ConfigProxyTest {

    @Test
    public void testProxyBinding() {
        Map<String, ConfigEntry> entries = new HashMap<>();
        long now = System.currentTimeMillis();
        entries.put("app.name", new ConfigEntry("app.name", "test-app", "mock", now));
        entries.put("app.max-db-connections", new ConfigEntry("app.max-db-connections", "50", "mock", now));
        entries.put("app.dev-mode", new ConfigEntry("app.dev-mode", "true", "mock", now));

        ConfigSnapshot snapshot = new ConfigSnapshot(100L, entries);

        ConfigManager manager = createMockManager(() -> snapshot);

        AppConfig config = manager.createProxy("app.", AppConfig.class);

        Assert.assertEquals("test-app", config.getName());
        Assert.assertEquals(50, config.getMaxDbConnections());
        Assert.assertTrue(config.isDevMode());

        // 测试快照锚定功能 (Pinning)
        AppConfig pinnedConfig = SnapshotAware.pin(config);
        Assert.assertNotNull(pinnedConfig);
        Assert.assertEquals(50, pinnedConfig.getMaxDbConnections());

        // 再次锚定应当返回自身对象实例
        Assert.assertSame(pinnedConfig, SnapshotAware.pin(pinnedConfig));
    }

    @Test
    public void testL2CacheEviction() {
        Map<String, ConfigEntry> entriesV1 = new HashMap<>();
        long now = System.currentTimeMillis();
        entriesV1.put("app.name", new ConfigEntry("app.name", "v1-app", "mock", now));
        ConfigSnapshot snapshotV1 = new ConfigSnapshot(1L, entriesV1);

        Map<String, ConfigEntry> entriesV2 = new HashMap<>();
        entriesV2.put("app.name", new ConfigEntry("app.name", "v2-app", "mock", now + 100));
        ConfigSnapshot snapshotV2 = new ConfigSnapshot(2L, entriesV2);

        AtomicReference<ConfigSnapshot> currentRef = new AtomicReference<>(snapshotV1);

        ConfigManager manager = createMockManager(currentRef::get);

        AppConfig config = manager.createProxy("app.", AppConfig.class);

        // 首次调用触发转换逻辑并缓存结果
        Assert.assertEquals("v1-app", config.getName());

        // 更新快照引用（模拟重载管理器的原子替换动作）
        currentRef.set(snapshotV2);

        // 第二次调用应检测到版本变化，触发二级缓存失效并拿取最新值
        Assert.assertEquals("v2-app", config.getName());
    }

    @Test
    public void testNamingCompatibility() {
        Map<String, ConfigEntry> entries = new HashMap<>();
        long now = System.currentTimeMillis();
        entries.put("app.name", new ConfigEntry("app.name", "camel", "mock", now));
        entries.put("app.max-db-connections", new ConfigEntry("app.max-db-connections", "10", "mock", now));
        entries.put("app.server_port", new ConfigEntry("app.server_port", "8080", "mock", now));
        entries.put("app.dev.mode", new ConfigEntry("app.dev.mode", "true", "mock", now));

        ConfigSnapshot snapshot = new ConfigSnapshot(1L, entries);
        ConfigManager manager = createMockManager(() -> snapshot);

        AppConfig config = manager.createProxy("app.", AppConfig.class);

        Assert.assertEquals("camel", config.getName());
        Assert.assertEquals(10, config.getMaxDbConnections());
        Assert.assertEquals(8080, config.getServerPort());
        Assert.assertTrue(config.isDevMode());
    }

    @Test
    public void testNestedBeanProxy() {
        Map<String, ConfigEntry> entries = new HashMap<>();
        long now = System.currentTimeMillis();
        entries.put("server.name", new ConfigEntry("server.name", "nested-app", "mock", now));
        entries.put("server.db.url",
                new ConfigEntry("server.db.url", "jdbc:mysql://localhost:3306/nested", "mock", now));

        ConfigSnapshot snapshot = new ConfigSnapshot(100L, entries);
        ConfigManager manager = createMockManager(() -> snapshot);

        ServerConfig config = manager.createProxy("server", ServerConfig.class);

        Assert.assertEquals("nested-app", config.getName());
        Assert.assertNotNull(config.getDb());
        Assert.assertEquals("jdbc:mysql://localhost:3306/nested", config.getDb().getUrl());
    }

    private ConfigManager createMockManager(java.util.function.Supplier<ConfigSnapshot> snapshotSupplier) {
        return new ConfigManager() {
            private final PropertyConverterRegistry converterRegistry = new PropertyConverterRegistry();

            @Override
            public ConfigSnapshot currentSnapshot() {
                return snapshotSupplier.get();
            }

            @Override
            public <T> T createProxy(String prefix, Class<T> type) {
                return new ConfigProxyFactory(converterRegistry).createLiveProxy(this, prefix, type);
            }

            @Override
            public void addChangeListener(String keyPattern, ConfigChangeListener listener) {
            }
        };
    }

    public static class ServerConfig {
        private String name;
        private DbConfig db;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public DbConfig getDb() {
            return db;
        }

        public void setDb(DbConfig db) {
            this.db = db;
        }
    }

    public static class DbConfig {
        private String url;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }
    }

    public static class AppConfig {
        private String name;
        private int maxDbConnections;
        private boolean devMode;
        private int serverPort;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getMaxDbConnections() {
            return maxDbConnections;
        }

        public void setMaxDbConnections(int maxDbConnections) {
            this.maxDbConnections = maxDbConnections;
        }

        public boolean isDevMode() {
            return devMode;
        }

        public void setDevMode(boolean devMode) {
            this.devMode = devMode;
        }

        public int getServerPort() {
            return serverPort;
        }

        public void setServerPort(int serverPort) {
            this.serverPort = serverPort;
        }
    }
}
