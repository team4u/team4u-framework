package com.team4u.framework.config.core;

import com.team4u.framework.config.core.annotation.ConfigDefault;
import com.team4u.framework.config.core.annotation.ConfigKey;
import com.team4u.framework.config.core.annotation.ConfigRequired;
import com.team4u.framework.config.core.convert.PropertyConverterRegistry;
import com.team4u.framework.config.core.domain.ConfigEntry;
import com.team4u.framework.config.core.domain.ConfigMissingException;
import com.team4u.framework.config.core.domain.ConfigSnapshot;
import com.team4u.framework.config.core.proxy.ConfigProxyFactory;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * 配置注解功能单元测试
 *
 * @author jay.wu
 */
public class ConfigAnnotationTest {

    @Test
    public void testConfigKey() {
        Map<String, ConfigEntry> entries = new HashMap<>();
        long now = System.currentTimeMillis();
        // 相对路径映射
        entries.put("oss.max_keys_count", new ConfigEntry("oss.max_keys_count", "100", "mock", now));
        // 绝对路径映射
        entries.put("aliyun.oss.endpoint", new ConfigEntry("aliyun.oss.endpoint", "http://oss.com", "mock", now));

        ConfigSnapshot snapshot = new ConfigSnapshot(1L, entries);
        ConfigManager manager = createMockManager(snapshot);

        OssConfig config = manager.createProxy("oss", OssConfig.class);

        // 测试相对路径映射
        Assert.assertEquals(100, config.maxKeys());
        // 测试绝对路径映射
        Assert.assertEquals("http://oss.com", config.endpoint());
    }

    @Test
    public void testConfigRequired() {
        Map<String, ConfigEntry> entries = new HashMap<>();
        ConfigSnapshot snapshot = new ConfigSnapshot(1L, entries);
        ConfigManager manager = createMockManager(snapshot);

        DbConfig config = manager.createProxy("db", DbConfig.class);

        // url 标记了 @ConfigRequired 且无默认值，应抛出异常
        try {
            config.url();
            Assert.fail("Should throw ConfigMissingException");
        } catch (ConfigMissingException e) {
            Assert.assertTrue(e.getMessage().contains("db.url"));
        }

        // username 标记了 @ConfigRequired 但有 @ConfigDefault，不应报错
        Assert.assertEquals("root", config.username());
    }

    @Test
    public void testComplexCombination() {
        Map<String, ConfigEntry> entries = new HashMap<>();
        long now = System.currentTimeMillis();
        entries.put("gateway.prod.url", new ConfigEntry("gateway.prod.url", "http://gateway.com", "mock", now));

        ConfigSnapshot snapshot = new ConfigSnapshot(1L, entries);
        ConfigManager manager = createMockManager(snapshot);

        PaymentConfig config = manager.createProxy("payment", PaymentConfig.class);

        Assert.assertEquals("http://gateway.com", config.gatewayUrl());
        Assert.assertEquals(3000L, config.timeoutMs());
    }

    private ConfigManager createMockManager(ConfigSnapshot snapshot) {
        return new ConfigManager() {
            private final PropertyConverterRegistry converterRegistry = new PropertyConverterRegistry();

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
        };
    }

    public interface OssConfig {
        // 别名映射: oss.max-keys -> oss.max_keys_count
        @ConfigKey("max_keys_count")
        int maxKeys();

        // 强制映射: oss.endpoint -> aliyun.oss.endpoint
        @ConfigKey(".aliyun.oss.endpoint")
        String endpoint();
    }

    public interface DbConfig {
        @ConfigRequired
        String url();

        @ConfigRequired
        @ConfigDefault("root")
        String username();
    }

    public interface PaymentConfig {
        @ConfigKey(".gateway.prod.url")
        @ConfigRequired
        String gatewayUrl();

        @ConfigDefault("3000")
        long timeoutMs();
    }
}
