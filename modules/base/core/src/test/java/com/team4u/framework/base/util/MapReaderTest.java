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
        Assert.assertTrue(reader.isEmpty());
        Assert.assertEquals(0, reader.size());
        Assert.assertNotNull(reader.toMap());
        Assert.assertNotNull(reader.getReader("any"));
        Assert.assertTrue(reader.getReader("any").isEmpty());
    }

    @Test
    public void testGetReaderAndMapHelpers() {
        Map<String, Object> innerMap = new HashMap<>();
        innerMap.put("host", "127.0.0.1");
        innerMap.put("port", 6379);

        Map<String, Object> rootMap = new HashMap<>();
        rootMap.put("name", "app");
        rootMap.put("redis", innerMap);

        MapReader rootReader = MapReader.of(rootMap);
        Assert.assertFalse(rootReader.isEmpty());
        Assert.assertEquals(2, rootReader.size());
        Assert.assertSame(rootMap, rootReader.toMap());

        MapReader redisReader = rootReader.getReader("redis");
        Assert.assertFalse(redisReader.isEmpty());
        Assert.assertEquals("127.0.0.1", redisReader.getString("host"));
        Assert.assertEquals(Integer.valueOf(6379), redisReader.getInt("port"));

        // Non-existent key returns empty MapReader
        MapReader missingReader = rootReader.getReader("missing");
        Assert.assertNotNull(missingReader);
        Assert.assertTrue(missingReader.isEmpty());
        Assert.assertNull(missingReader.getString("host"));

        // Non-map value returns empty MapReader
        MapReader nameReader = rootReader.getReader("name");
        Assert.assertNotNull(nameReader);
        Assert.assertTrue(nameReader.isEmpty());
    }

    @Test
    public void testToBeanBasicTypesAndNamingConventions() {
        Map<String, Object> subMap = new HashMap<>();
        subMap.put("host", "127.0.0.1");
        subMap.put("port", 6379);

        Map<String, Object> map = new HashMap<>();
        map.put("server-name", "demoServer");
        map.put("server_port", 8080);
        map.put("MAX-ATTEMPTS", "5");
        map.put("enable_ssl", "true");
        map.put("action", "reject");
        map.put("timeout", "10s");
        map.put("sub", subMap);

        MapReader reader = MapReader.of(map);
        TestServerConfig config = reader.toBean(TestServerConfig.class);

        Assert.assertNotNull(config);
        Assert.assertEquals("demoServer", config.getServerName());
        Assert.assertEquals(8080, config.getServerPort());
        Assert.assertEquals(Long.valueOf(5L), config.getMaxAttempts());
        Assert.assertTrue(config.getEnableSsl());
        Assert.assertEquals(Action.REJECT, config.getAction());
        Assert.assertEquals(Duration.ofSeconds(10), config.getTimeout());
        Assert.assertNotNull(config.getSub());
        Assert.assertEquals("127.0.0.1", config.getSub().getHost());
        Assert.assertEquals(6379, config.getSub().getPort());
    }

    @Test
    public void testToBeanChainedNavigation() {
        Map<String, Object> subMap = new HashMap<>();
        subMap.put("host", "192.168.1.100");
        subMap.put("port", 3306);

        Map<String, Object> rootMap = new HashMap<>();
        rootMap.put("db", subMap);

        MapReader rootReader = MapReader.of(rootMap);
        TestSubConfig dbConfig = rootReader.getReader("db").toBean(TestSubConfig.class);

        Assert.assertNotNull(dbConfig);
        Assert.assertEquals("192.168.1.100", dbConfig.getHost());
        Assert.assertEquals(3306, dbConfig.getPort());
    }

    @Test
    public void testToBeanNullSafetyAndFaultTolerance() {
        Map<String, Object> map = new HashMap<>();
        map.put("host", "localhost");
        map.put("port", 80);

        MapReader reader = MapReader.of(map);

        // 1. null map
        Assert.assertNull(MapReader.of(null).toBean(TestSubConfig.class));
        Assert.assertNull(MapReader.of(null).toBean(TestSubConfig.class, CopyOptions.create()));

        // 2. empty map
        Assert.assertNull(MapReader.of(new HashMap<>()).toBean(TestSubConfig.class));
        Assert.assertNull(MapReader.of(new HashMap<>()).toBean(TestSubConfig.class, CopyOptions.create()));

        // 3. null class
        Assert.assertNull(reader.toBean(null));
        Assert.assertNull(reader.toBean(null, CopyOptions.create()));

        // 4. null options fallback to default options
        TestSubConfig beanWithNullOptions = reader.toBean(TestSubConfig.class, null);
        Assert.assertNotNull(beanWithNullOptions);
        Assert.assertEquals("localhost", beanWithNullOptions.getHost());

        // 5. non-existent sub reader
        Assert.assertNull(reader.getReader("nonExistent").toBean(TestSubConfig.class));
    }

    @Test
    public void testToBeanCustomCopyOptions() {
        Map<String, Object> map = new HashMap<>();
        map.put("server-name", "demoServer");
        map.put("serverPort", "invalid_number");

        MapReader reader = MapReader.of(map);

        // 1. Strict mode (case sensitive): "server-name" does not match "serverName"
        CopyOptions strictCaseOptions = CopyOptions.create().ignoreError();
        TestServerConfig strictCaseBean = reader.toBean(TestServerConfig.class, strictCaseOptions);
        Assert.assertNotNull(strictCaseBean);
        Assert.assertNull(strictCaseBean.getServerName());

        // 2. Strict error mode: parsing "invalid_number" into primitive int should throw exception when ignoreError is false
        CopyOptions strictErrorOptions = CopyOptions.create().ignoreCase();
        try {
            reader.toBean(TestServerConfig.class, strictErrorOptions);
            Assert.fail("Expected RuntimeException when ignoreError is false");
        } catch (RuntimeException e) {
            Assert.assertTrue(e.getMessage().contains("Set field error"));
        }

        // 3. Default loose mode ignores the error safely
        TestServerConfig looseBean = reader.toBean(TestServerConfig.class);
        Assert.assertNotNull(looseBean);
        Assert.assertEquals("demoServer", looseBean.getServerName());
        Assert.assertEquals(0, looseBean.getServerPort());
    }

    @lombok.Data
    public static class TestServerConfig {
        private String serverName;
        private int serverPort;
        private Long maxAttempts;
        private Boolean enableSsl;
        private Action action;
        private Duration timeout;
        private TestSubConfig sub;
    }

    @lombok.Data
    public static class TestSubConfig {
        private String host;
        private int port;
    }
}
