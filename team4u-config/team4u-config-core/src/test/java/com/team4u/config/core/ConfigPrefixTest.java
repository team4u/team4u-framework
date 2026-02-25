package com.team4u.config.core;

import com.team4u.config.core.annotation.ConfigPrefix;
import com.team4u.config.core.convert.PropertyConverterRegistry;
import com.team4u.config.core.domain.ConfigEntry;
import com.team4u.config.core.domain.ConfigSnapshot;
import com.team4u.config.core.internal.DefaultConfigManager;
import com.team4u.config.core.spi.ConfigSourceRegistry;
import com.team4u.config.core.spi.ConfigWatcherRegistry;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * @ConfigPrefix 功能单元测试
 */
public class ConfigPrefixTest {

    @Test
    public void testOnlyAnnotation() {
        Map<String, ConfigEntry> entries = new HashMap<>();
        long now = System.currentTimeMillis();
        entries.put("app.db.url", new ConfigEntry("app.db.url", "jdbc:mysql://localhost:3306/test", "mock", now));

        ConfigManager manager = createConfigManager(entries);

        // 使用不带前缀的方法，应自动识别注解中的前缀 "app.db"
        DbConfig config = manager.createProxy(DbConfig.class);
        Assert.assertEquals("jdbc:mysql://localhost:3306/test", config.url());
    }

    @Test
    public void testAnnotationAndParamAppend() {
        Map<String, ConfigEntry> entries = new HashMap<>();
        long now = System.currentTimeMillis();
        // 期望前缀: "prod" + "." + "app.db" = "prod.app.db"
        entries.put("prod.app.db.url", new ConfigEntry("prod.app.db.url", "jdbc:mysql://prod:3306/test", "mock", now));

        ConfigManager manager = createConfigManager(entries);

        // 显式传入 "prod" 前缀，应与注解叠加
        DbConfig config = manager.createProxy("prod", DbConfig.class);
        Assert.assertEquals("jdbc:mysql://prod:3306/test", config.url());
    }

    @Test
    public void testNoAnnotation() {
        Map<String, ConfigEntry> entries = new HashMap<>();
        long now = System.currentTimeMillis();
        entries.put("url", new ConfigEntry("url", "http://localhost", "mock", now));

        ConfigManager manager = createConfigManager(entries);

        // 接口无注解且不传前缀，应使用根路径
        NoAnnotationConfig config = manager.createProxy(NoAnnotationConfig.class);
        Assert.assertEquals("http://localhost", config.url());
    }

    private ConfigManager createConfigManager(Map<String, ConfigEntry> entries) {
        ConfigSnapshot snapshot = new ConfigSnapshot(1L, entries);

        ConfigSourceRegistry sourceRegistry = new ConfigSourceRegistry();
        ConfigWatcherRegistry watcherRegistry = new ConfigWatcherRegistry();
        PropertyConverterRegistry converterRegistry = new PropertyConverterRegistry();

        DefaultConfigManager manager = new DefaultConfigManager(sourceRegistry, watcherRegistry, converterRegistry,
                null) {
            @Override
            public ConfigSnapshot currentSnapshot() {
                return snapshot;
            }
        };
        return manager;
    }

    @ConfigPrefix("app.db")
    public interface DbConfig {
        String url();
    }

    public interface NoAnnotationConfig {
        String url();
    }
}
