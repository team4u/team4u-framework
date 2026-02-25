package com.team4u.config.core.internal;

import com.team4u.config.core.domain.ConfigEntry;
import com.team4u.config.core.domain.ConfigSnapshot;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class PlaceholderResolverTest {

    private ConfigSnapshot snapshot;

    @Before
    public void setUp() {
        Map<String, ConfigEntry> entries = new HashMap<>();
        entries.put("app.name", new ConfigEntry("app.name", "Team4UApp", "source", 1L));
        entries.put("db.host", new ConfigEntry("db.host", "127.0.0.1", "source", 1L));
        entries.put("db.port", new ConfigEntry("db.port", "3306", "source", 1L));
        // 嵌套占位符依赖
        entries.put("db.url", new ConfigEntry("db.url", "jdbc:mysql://${db.host}:${db.port}/test", "source", 1L));
        // 循环依赖
        entries.put("loop.a", new ConfigEntry("loop.a", "${loop.b}", "source", 1L));
        entries.put("loop.b", new ConfigEntry("loop.b", "${loop.a}", "source", 1L));
        // 层层递进解析
        entries.put("nested.root", new ConfigEntry("nested.root", "${nested.level1}", "source", 1L));
        entries.put("nested.level1", new ConfigEntry("nested.level1", "${nested.level2}", "source", 1L));
        entries.put("nested.level2", new ConfigEntry("nested.level2", "Leaf", "source", 1L));

        snapshot = new ConfigSnapshot(1L, entries);
    }

    @Test
    public void testResolveSimple() {
        String result = PlaceholderResolver.resolve("Hello ${app.name}", snapshot);
        Assert.assertEquals("Hello Team4UApp", result);
    }

    @Test
    public void testResolveWithDefault() {
        String result = PlaceholderResolver.resolve("Port is ${app.port:8080}", snapshot);
        Assert.assertEquals("Port is 8080", result);
    }

    @Test
    public void testResolveNested() {
        String result = PlaceholderResolver.resolve("DB URL: ${db.url}", snapshot);
        Assert.assertEquals("DB URL: jdbc:mysql://127.0.0.1:3306/test", result);
    }

    @Test
    public void testResolveDeepNested() {
        String result = PlaceholderResolver.resolve("Value: ${nested.root}", snapshot);
        Assert.assertEquals("Value: Leaf", result);
    }

    @Test
    public void testCircularDependency() {
        IllegalArgumentException exception = Assert.assertThrows(IllegalArgumentException.class, () -> {
            PlaceholderResolver.resolve("Test ${loop.a}", snapshot);
        });
        Assert.assertTrue(exception.getMessage().contains("Circular dependency detected"));
    }

    @Test
    public void testUnresolvedKeepIntact() {
        String result = PlaceholderResolver.resolve("Missing ${not.exist}", snapshot);
        Assert.assertEquals("Missing ${not.exist}", result); // 优雅降级保持原样
    }

    @Test
    public void testMultiplePlaceholders() {
        String result = PlaceholderResolver.resolve("${app.name} runs on ${db.host}:${db.port}", snapshot);
        Assert.assertEquals("Team4UApp runs on 127.0.0.1:3306", result);
    }
}
