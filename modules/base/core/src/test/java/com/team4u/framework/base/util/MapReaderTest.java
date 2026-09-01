package com.team4u.framework.base.util;

import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * MapReader 单元测试
 *
 * @author jay.wu
 */
public class MapReaderTest {

    enum Action {
        FAIL,
        REJECT
    }

    @Test
    public void testReadBasicTypes() {
        Map<String, Object> map = new HashMap<>();
        map.put("name", "orderFlow");
        map.put("count", "10");
        map.put("limit", 100L);
        map.put("enabled", "true");
        map.put("timeout", "5s");
        map.put("action", "reject");
        map.put("score", 98.5);

        MapReader reader = MapReader.of(map);

        Assert.assertEquals("orderFlow", reader.getString("name"));
        Assert.assertEquals(Integer.valueOf(10), reader.getInt("count"));
        Assert.assertEquals(Long.valueOf(100L), reader.getLong("limit"));
        Assert.assertTrue(reader.getBoolean("enabled"));
        Assert.assertEquals(Duration.ofSeconds(5), reader.getDuration("timeout"));
        Assert.assertEquals(Action.REJECT, reader.getEnum(Action.class, "action"));
        Assert.assertEquals(Double.valueOf(98.5), reader.getDouble("score"));

        // MapUtil.reader entry point
        MapReader utilReader = MapUtil.reader(map);
        Assert.assertEquals("orderFlow", utilReader.getString("name"));

        // Constructor
        MapReader newReader = new MapReader(map);
        Assert.assertEquals("orderFlow", newReader.getString("name"));
    }

    @Test
    public void testAliasesAndDefaults() {
        Map<String, Object> map = new HashMap<>();
        map.put("max-attempts", 3);
        map.put("retry-interval", 500);
        map.put("rate", 4.5);
        map.put("alias_long", "999");
        map.put("alias_bool", "yes");

        MapReader reader = MapReader.of(map);

        Assert.assertEquals(Integer.valueOf(3), reader.getInt("maxAttempts", 1, "max-attempts"));
        Assert.assertEquals(Duration.ofMillis(500), reader.getDuration("interval", null, "retry-interval"));
        Assert.assertEquals(Double.valueOf(4.5), reader.getDouble("rate"));
        Assert.assertEquals(Double.valueOf(2.5), reader.getDouble("missingRate", 2.5, "non-existent"));
        Assert.assertEquals(Long.valueOf(999L), reader.getLong("myLong", 100L, "alias_long"));
        Assert.assertTrue(reader.getBoolean("myBool", false, "alias_bool"));
        Assert.assertEquals("defaultVal", reader.getString("nonExistent", "defaultVal"));
        Assert.assertEquals(Integer.valueOf(10), reader.getInt("missing", 10));
        Assert.assertTrue(reader.containsKey("max-attempts"));
        Assert.assertTrue(reader.containsKey("maxAttempts", "max-attempts"));
        Assert.assertFalse(reader.containsKey("unknownKey"));
    }

    @Test
    public void testGenericGet() {
        Map<String, Object> map = new HashMap<>();
        map.put("count", "123");
        map.put("enumStr", "fail");

        MapReader reader = MapReader.of(map);
        Assert.assertEquals(Integer.valueOf(123), reader.get(Integer.class, "count"));
        Assert.assertEquals(Integer.valueOf(123), reader.get(Integer.class, "count", 456));
        Assert.assertEquals(Integer.valueOf(456), reader.get(Integer.class, "missing", 456));
        Assert.assertEquals(Action.FAIL, reader.get(Action.class, "enumStr"));
        Assert.assertEquals(Action.FAIL, reader.getEnum(Action.class, "missing", Action.FAIL));
    }

    @Test
    public void testRequire() {
        Map<String, Object> map = new HashMap<>();
        map.put("key", "testKey");
        map.put("aliasKey", "aliasVal");

        MapReader reader = MapReader.of(map);
        Assert.assertEquals("testKey", reader.requireString("key", "Key required"));
        Assert.assertEquals("aliasVal", reader.requireString("missingMain", "Alias required", "aliasKey"));
        Assert.assertEquals("testKey", reader.require("key", "Raw key required"));

        try {
            reader.requireString("missingKey", "Missing key required");
            Assert.fail("Expected exception");
        } catch (IllegalArgumentException ex) {
            Assert.assertTrue(ex.getMessage().contains("Missing key required"));
        }

        try {
            reader.require("missingKey", null);
            Assert.fail("Expected exception");
        } catch (IllegalArgumentException ex) {
            Assert.assertTrue(ex.getMessage().contains("Missing required configuration: missingKey"));
        }
    }

    @Test
    public void testNullSafety() {
        MapReader reader = MapReader.of(null);
        Assert.assertNull(reader.getRaw("any"));
        Assert.assertNull(reader.getString("any"));
        Assert.assertNull(reader.getInt("any"));
        Assert.assertNull(reader.getLong("any"));
        Assert.assertNull(reader.getDouble("any"));
        Assert.assertNull(reader.getBoolean("any"));
        Assert.assertNull(reader.getDuration("any"));
        Assert.assertNull(reader.getEnum(Action.class, "any"));
        Assert.assertNull(reader.get(String.class, "any"));
        Assert.assertFalse(reader.containsKey("any"));
        Assert.assertEquals("default", reader.getString("any", "default"));
        Assert.assertEquals(Integer.valueOf(1), reader.getInt("any", 1));
        Assert.assertEquals(Long.valueOf(2L), reader.getLong("any", 2L));
        Assert.assertEquals(Double.valueOf(3.0), reader.getDouble("any", 3.0));
        Assert.assertTrue(reader.getBoolean("any", true));
        Assert.assertEquals(Duration.ofSeconds(1), reader.getDuration("any", Duration.ofSeconds(1)));
        Assert.assertEquals(Action.FAIL, reader.getEnum(Action.class, "any", Action.FAIL));
        Assert.assertEquals("custom", reader.get(String.class, "any", "custom"));
    }
}
