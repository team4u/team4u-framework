package com.team4u.framework.flow.definition.util;

import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public class ConfigMapReaderTest {

    enum Action {
        FAIL,
        REJECT
    }

    @Test
    public void testReadBasicTypes() {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("name", "orderFlow");
        map.put("count", "10");
        map.put("limit", 100L);
        map.put("enabled", "true");
        map.put("timeout", "5s");
        map.put("action", "reject");

        ConfigMapReader reader = ConfigMapReader.of(map);

        Assert.assertEquals("orderFlow", reader.getString("name"));
        Assert.assertEquals(Integer.valueOf(10), reader.getInt("count"));
        Assert.assertEquals(Long.valueOf(100L), reader.getLong("limit"));
        Assert.assertTrue(reader.getBoolean("enabled"));
        Assert.assertEquals(Duration.ofSeconds(5), reader.getDuration("timeout"));
        Assert.assertEquals(Action.REJECT, reader.getEnum(Action.class, "action"));
    }

    @Test
    public void testAliasesAndDefaults() {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("max-attempts", 3);
        map.put("retry-interval", 500);
        map.put("rate", 4.5);

        ConfigMapReader reader = ConfigMapReader.of(map);

        Assert.assertEquals(Integer.valueOf(3), reader.getInt("maxAttempts", 1, "max-attempts"));
        Assert.assertEquals(Duration.ofMillis(500), reader.getDuration("interval", null, "retry-interval"));
        Assert.assertEquals(Double.valueOf(4.5), reader.getDouble("rate"));
        Assert.assertEquals("defaultVal", reader.getString("nonExistent", "defaultVal"));
        Assert.assertEquals(Integer.valueOf(10), reader.getInt("missing", 10));
        Assert.assertTrue(reader.containsKey("max-attempts"));
        Assert.assertTrue(reader.containsKey("maxAttempts", "max-attempts"));
        Assert.assertFalse(reader.containsKey("unknownKey"));
    }

    @Test
    public void testRequire() {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("key", "testKey");

        ConfigMapReader reader = ConfigMapReader.of(map);
        Assert.assertEquals("testKey", reader.requireString("key", "Key required"));

        try {
            reader.requireString("missingKey", "Missing key required");
            Assert.fail("Expected exception");
        } catch (Exception ex) {
            Assert.assertTrue(ex.getMessage().contains("Missing key required"));
        }
    }

    @Test
    public void testNullSafety() {
        ConfigMapReader reader = ConfigMapReader.of(null);
        Assert.assertNull(reader.getString("any"));
        Assert.assertNull(reader.getInt("any"));
        Assert.assertNull(reader.getLong("any"));
        Assert.assertNull(reader.getDouble("any"));
        Assert.assertNull(reader.getBoolean("any"));
        Assert.assertNull(reader.getDuration("any"));
        Assert.assertNull(reader.getEnum(Action.class, "any"));
        Assert.assertFalse(reader.containsKey("any"));
    }
}
