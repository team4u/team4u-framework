package com.team4u.config.core.internal;

import com.team4u.config.core.domain.ConfigEntry;
import com.team4u.config.core.domain.ConfigSnapshot;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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

    @Test
    public void testNestedPlaceholderInKey() {
        Map<String, ConfigEntry> entries = new HashMap<>();
        entries.put("env", new ConfigEntry("env", "dev", "source", 1L));
        entries.put("db.dev.host", new ConfigEntry("db.dev.host", "localhost", "source", 1L));
        ConfigSnapshot nestedSnapshot = new ConfigSnapshot(2L, entries);

        String result = PlaceholderResolver.resolve("Host: ${db.${env}.host}", nestedSnapshot);
        Assert.assertEquals("Host: localhost", result);
    }

    @Test
    public void testSharedSet() {
        Set<String> visitedKeys = new HashSet<>();
        String result1 = PlaceholderResolver.resolve("Hello ${app.name}", snapshot, visitedKeys);
        Assert.assertEquals("Hello Team4UApp", result1);
        Assert.assertTrue(visitedKeys.isEmpty());

        String result2 = PlaceholderResolver.resolve("DB: ${db.host}", snapshot, visitedKeys);
        Assert.assertEquals("DB: 127.0.0.1", result2);
        Assert.assertTrue(visitedKeys.isEmpty());
    }

    @Test
    public void testNestedPlaceholderInDefaultValue() {
        // 1. 简单的默认值嵌套：${missing.key:${db.host}} -> 127.0.0.1
        String result1 = PlaceholderResolver.resolve("Host: ${missing.key:${db.host}}", snapshot);
        Assert.assertEquals("Host: 127.0.0.1", result1);

        // 2. 复杂的默认值嵌套：${missing.key:http://${db.host}:${db.port}/} -> http://127.0.0.1:3306/
        String result2 = PlaceholderResolver.resolve("URL: ${missing.key:http://${db.host}:${db.port}/}", snapshot);
        Assert.assertEquals("URL: http://127.0.0.1:3306/", result2);

        // 3. 带有嵌套默认值的嵌套：${missing.key:${another.missing:8080}} -> 8080
        String result3 = PlaceholderResolver.resolve("Port: ${missing.key:${another.missing:8080}}", snapshot);
        Assert.assertEquals("Port: 8080", result3);
    }
}
