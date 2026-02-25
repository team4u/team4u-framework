package com.team4u.config.core;

import com.team4u.config.core.annotation.ConfigDefault;
import com.team4u.config.core.convert.PropertyConverterRegistry;
import com.team4u.config.core.domain.ConfigEntry;
import com.team4u.config.core.domain.ConfigSnapshot;
import com.team4u.config.core.proxy.ConfigProxyFactory;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 验证 @ConfigDefault 注解功能的单元测试
 */
public class ConfigDefaultTest {

    @Test
    public void testDefaultValue() {
        ConfigSnapshot snapshot = new ConfigSnapshot(1L, Collections.emptyMap());
        ConfigManager manager = new MockConfigManager(snapshot);

        DefaultConfig config = manager.createProxy("app", DefaultConfig.class);

        // 验证基本默认值
        Assert.assertEquals("localhost", config.serverName());
        Assert.assertEquals(8080, config.port());
        Assert.assertTrue(config.enableCache());

        // 验证没有注解时的 Java 类型默认值
        Assert.assertNull(config.otherSetting());
        Assert.assertEquals(0, config.retryCount());
    }

    @Test
    public void testValueOverride() {
        Map<String, ConfigEntry> entries = new HashMap<>();
        long now = System.currentTimeMillis();
        entries.put("app.serverName", new ConfigEntry("app.serverName", "127.0.0.1", "test", now));
        entries.put("app.port", new ConfigEntry("app.port", "9090", "test", now));

        ConfigSnapshot snapshot = new ConfigSnapshot(100L, entries);
        ConfigManager manager = new MockConfigManager(snapshot);

        DefaultConfig config = manager.createProxy("app", DefaultConfig.class);

        // 验证配置值覆盖默认值
        Assert.assertEquals("127.0.0.1", config.serverName());
        Assert.assertEquals(9090, config.port());
        // 没配的依然使用默认值
        Assert.assertTrue(config.enableCache());
    }

    @Test
    public void testInvalidDefaultValue() {
        ConfigSnapshot snapshot = new ConfigSnapshot(1L, Collections.emptyMap());
        ConfigManager manager = new MockConfigManager(snapshot);

        InvalidDefaultConfig config = manager.createProxy("app", InvalidDefaultConfig.class);

        // 验证错误的默认值格式会回退到类型默认值
        Assert.assertEquals(0, config.invalidPort());
    }

    @Test
    public void testNestedInterfaceWithoutConfig() {
        ConfigSnapshot snapshot = new ConfigSnapshot(1L, Collections.emptyMap());
        ConfigManager manager = new MockConfigManager(snapshot);

        NestedConfig config = manager.createProxy("app", NestedConfig.class);

        // 验证嵌套接口即便没有配置，也应该返回代理对象而非 null
        Assert.assertNotNull(config.subConfig());
        // 验证子配置依然可以应用其默认值
        Assert.assertEquals("nested", config.subConfig().name());
    }

    public interface DefaultConfig {

        @ConfigDefault("localhost")
        String serverName();

        @ConfigDefault("8080")
        int port();

        @ConfigDefault("true")
        boolean enableCache();

        String otherSetting();

        int retryCount();
    }

    public interface InvalidDefaultConfig {
        @ConfigDefault("not-a-number")
        int invalidPort();
    }

    public interface NestedConfig {
        SubConfig subConfig();
    }

    public interface SubConfig {
        @ConfigDefault("nested")
        String name();
    }

    /**
     * 简单的测试用配置管理器实现
     */
    private static class MockConfigManager implements ConfigManager {
        private final ConfigSnapshot snapshot;
        private final PropertyConverterRegistry converterRegistry = new PropertyConverterRegistry();

        public MockConfigManager(ConfigSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public ConfigSnapshot currentSnapshot() {
            return snapshot;
        }

        @Override
        public <T> T createProxy(String prefix, Class<T> interfaceType) {
            return new ConfigProxyFactory(converterRegistry).createLiveProxy(this, prefix, interfaceType);
        }

        @Override
        public void addChangeListener(String keyPattern, ConfigChangeListener listener) {
        }
    }
}
