package com.team4u.framework.config.core;

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
        Assert.assertEquals(100, config.getMaxKeys());
        // 测试绝对路径映射
        Assert.assertEquals("http://oss.com", config.getEndpoint());
    }

    @Test
    public void testConfigRequired() {
        Map<String, ConfigEntry> entries = new HashMap<>();
        ConfigSnapshot snapshot = new ConfigSnapshot(1L, entries);
        ConfigManager manager = createMockManager(snapshot);

        DbConfig config = manager.createProxy("db", DbConfig.class);

        // url 标记了 @ConfigRequired 且无配置，应抛出异常
        try {
            config.getUrl();
            Assert.fail("Should throw ConfigMissingException");
        } catch (ConfigMissingException e) {
            Assert.assertTrue(e.getMessage().contains("db.url"));
        }

        // username 有字段初始值，@ConfigRequired 不会触发异常
        Assert.assertEquals("root", config.getUsername());
    }

    @Test
    public void testComplexCombination() {
        Map<String, ConfigEntry> entries = new HashMap<>();
        long now = System.currentTimeMillis();
        entries.put("gateway.prod.url", new ConfigEntry("gateway.prod.url", "http://gateway.com", "mock", now));

        ConfigSnapshot snapshot = new ConfigSnapshot(1L, entries);
        ConfigManager manager = createMockManager(snapshot);

        PaymentConfig config = manager.createProxy("payment", PaymentConfig.class);

        Assert.assertEquals("http://gateway.com", config.getGatewayUrl());
        Assert.assertEquals(3000L, config.getTimeoutMs());
    }

    private ConfigManager createMockManager(ConfigSnapshot snapshot) {
        return new ConfigManager() {
            private final PropertyConverterRegistry converterRegistry = new PropertyConverterRegistry();

            @Override
            public ConfigSnapshot currentSnapshot() {
                return snapshot;
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

    public static class OssConfig {
        // 别名映射: getMaxKeys -> oss.max_keys_count
        @ConfigKey("max_keys_count")
        private int maxKeys;

        // 强制映射: getEndpoint -> aliyun.oss.endpoint
        @ConfigKey(".aliyun.oss.endpoint")
        private String endpoint;

        public int getMaxKeys() {
            return maxKeys;
        }

        public void setMaxKeys(int maxKeys) {
            this.maxKeys = maxKeys;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }
    }

    public static class DbConfig {
        @ConfigRequired
        private String url;

        // 字段初始值作为默认值，@ConfigRequired 与字段初始值共存时，只要有初始值就不抛异常
        @ConfigRequired
        private String username = "root";

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }
    }

    public static class PaymentConfig {
        @ConfigKey(".gateway.prod.url")
        @ConfigRequired
        private String gatewayUrl;

        // 字段初始值作为默认值
        private long timeoutMs = 3000L;

        public String getGatewayUrl() {
            return gatewayUrl;
        }

        public void setGatewayUrl(String gatewayUrl) {
            this.gatewayUrl = gatewayUrl;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }
}
