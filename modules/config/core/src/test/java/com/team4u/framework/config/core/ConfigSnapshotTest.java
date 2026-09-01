package com.team4u.framework.config.core;

import com.team4u.framework.base.util.MapReader;
import com.team4u.framework.config.core.domain.ConfigEntry;
import com.team4u.framework.config.core.domain.ConfigSnapshot;
import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public class ConfigSnapshotTest {

    @Test
    public void testGetAndPrefix() {
        Map<String, ConfigEntry> entries = new HashMap<>();
        long now = System.currentTimeMillis();
        entries.put("app.name", new ConfigEntry("app.name", "test", "src", now));
        entries.put("db.mysql.url", new ConfigEntry("db.mysql.url", "jdbc:mysql://localhost", "src", now));
        entries.put("db.mysql.user", new ConfigEntry("db.mysql.user", "root", "src", now));
        // 已删除的数据项 (Tombstone 语义)
        entries.put("db.mysql.pwd", new ConfigEntry("db.mysql.pwd", null, "src", now));

        ConfigSnapshot snapshot = new ConfigSnapshot(1L, entries);

        // 测试精确匹配获取
        Assert.assertEquals("test", snapshot.get("app.name").orElse(null));
        Assert.assertFalse(snapshot.get("not.exist").isPresent());
        Assert.assertFalse(snapshot.get("db.mysql.pwd").isPresent());

        // 测试基于前缀的子集获取
        Map<String, String> dbProps = snapshot.getByPrefix("db.mysql");
        Assert.assertEquals(2, dbProps.size());
        Assert.assertEquals("jdbc:mysql://localhost", dbProps.get("url"));
        Assert.assertEquals("root", dbProps.get("user"));
        Assert.assertNull("已删除的项不应出现在前缀搜索结果中", dbProps.get("pwd"));

        // 测试带有尾部点号的前缀逻辑处理
        Map<String, String> dbPropsWithDot = snapshot.getByPrefix("db.mysql.");
        Assert.assertEquals(2, dbPropsWithDot.size());
        Assert.assertEquals("root", dbPropsWithDot.get("user"));
    }

    @Test
    public void testGetUnflattenedValue() {
        Map<String, ConfigEntry> entries = new HashMap<>();
        long now = System.currentTimeMillis();
        entries.put("server.db.url", new ConfigEntry("server.db.url", "jdbc", "src", now));

        ConfigSnapshot snapshot = new ConfigSnapshot(1L, entries);

        // 普通前缀
        Object val1 = snapshot.getUnflattenedValue("server.db");
        Assert.assertTrue(val1 instanceof Map);
        Assert.assertEquals("jdbc", ((Map<?, ?>) val1).get("url"));

        // 带点的后缀
        Object val2 = snapshot.getUnflattenedValue("server.db.");
        Assert.assertTrue(val2 instanceof Map);
        Assert.assertEquals("jdbc", ((Map<?, ?>) val2).get("url"));

        // 验证一致性
        Assert.assertEquals(val1, val2);
    }

    @Test
    public void testNormalize() {
        Assert.assertEquals("serverport", ConfigSnapshot.normalize("server.port"));
        Assert.assertEquals("serverport", ConfigSnapshot.normalize("server-port"));
        Assert.assertEquals("serverport", ConfigSnapshot.normalize("server_port"));
        Assert.assertEquals("serverport", ConfigSnapshot.normalize("ServerPort"));
        Assert.assertEquals("serverport", ConfigSnapshot.normalize("SERVER_PORT"));
        Assert.assertNull(ConfigSnapshot.normalize(null));
    }

    @Test
    public void testGetSmart() {
        Map<String, ConfigEntry> entries = new HashMap<>();
        long now = System.currentTimeMillis();
        entries.put("app.server-port", new ConfigEntry("app.server-port", "8080", "src", now));
        entries.put("db_url", new ConfigEntry("db_url", "jdbc", "src", now));

        ConfigSnapshot snapshot = new ConfigSnapshot(1L, entries);

        // 1. 精确匹配
        Assert.assertEquals("8080", snapshot.getSmart("app.server-port").orElse(null));

        // 2. 松散匹配 (驼峰 -> 中划线)
        Assert.assertEquals("8080", snapshot.getSmart("appServerPort").orElse(null));

        // 3. 松散匹配 (点分隔 -> 中划线)
        Assert.assertEquals("8080", snapshot.getSmart("app.serverPort").orElse(null));

        // 4. 下划线转换
        Assert.assertEquals("jdbc", snapshot.getSmart("dbUrl").orElse(null));

        // 5. 不存在的情况
        Assert.assertFalse(snapshot.getSmart("notExist").isPresent());
    }

    @Test
    public void testGetSmartCollisionUsesDeterministicWinner() {
        Map<String, ConfigEntry> entries = new HashMap<>();
        long now = System.currentTimeMillis();
        entries.put("APP_PORT", new ConfigEntry("APP_PORT", "7070", "env", now));
        entries.put("app.port", new ConfigEntry("app.port", "8080", "prop", now));

        ConfigSnapshot snapshot = new ConfigSnapshot(1L, entries);

        Assert.assertEquals("8080", snapshot.getSmart("appPort").orElse(null));
    }

    @Test
    public void testToString() {
        Map<String, ConfigEntry> entries = new HashMap<>();
        long now = System.currentTimeMillis();
        for (int i = 0; i < 15; i++) {
            String key = "key" + i;
            entries.put(key, new ConfigEntry(key, "value" + i, "src", now));
        }

        ConfigSnapshot snapshot = new ConfigSnapshot(12345L, entries);
        String str = snapshot.toString();

        // 验证包含必要信息
        Assert.assertTrue("输出应包含版本号", str.contains("version=12345"));
        Assert.assertTrue("输出应包含条目总数", str.contains("entriesCount=15"));
        Assert.assertTrue("输出应包含摘要标记", str.contains("entriesSummary=["));
        Assert.assertTrue("超过 10 个条目应包含省略号", str.contains("..."));
        Assert.assertTrue("输出应包含松散索引大小", str.contains("looseIndexSize="));
        Assert.assertTrue("输出应包含结构化图根节点", str.contains("unflattenedMapRoots="));
    }

    @Test
    public void testAsReaderRoot() {
        Map<String, ConfigEntry> entries = new HashMap<>();
        long now = System.currentTimeMillis();
        entries.put("app.name", new ConfigEntry("app.name", "my-service", "src", now));
        entries.put("spring.redis.host", new ConfigEntry("spring.redis.host", "10.0.0.1", "src", now));
        entries.put("spring.redis.port", new ConfigEntry("spring.redis.port", "6379", "src", now));
        entries.put("spring.redis.timeout", new ConfigEntry("spring.redis.timeout", "2s", "src", now));

        ConfigSnapshot snapshot = new ConfigSnapshot(1L, entries);
        MapReader reader = snapshot.asReader();

        Assert.assertNotNull(reader);
        Assert.assertFalse(reader.isEmpty());

        // 导航至子树
        MapReader springReader = reader.getReader("spring");
        Assert.assertNotNull(springReader);
        Assert.assertFalse(springReader.isEmpty());

        MapReader redisReader = springReader.getReader("redis");
        Assert.assertEquals("10.0.0.1", redisReader.getString("host"));
        Assert.assertEquals(Integer.valueOf(6379), redisReader.getInt("port"));
        Assert.assertEquals(Duration.ofSeconds(2), redisReader.getDuration("timeout"));
    }

    @Test
    public void testAsReaderWithPrefix() {
        Map<String, ConfigEntry> entries = new HashMap<>();
        long now = System.currentTimeMillis();
        entries.put("spring.redis.host", new ConfigEntry("spring.redis.host", "127.0.0.1", "src", now));
        entries.put("spring.redis.port", new ConfigEntry("spring.redis.port", "6379", "src", now));
        entries.put("spring.redis.timeout", new ConfigEntry("spring.redis.timeout", "500ms", "src", now));
        entries.put("spring.redis.max-attempts", new ConfigEntry("spring.redis.max-attempts", "3", "src", now));
        entries.put("spring.redis.enabled", new ConfigEntry("spring.redis.enabled", "true", "src", now));

        ConfigSnapshot snapshot = new ConfigSnapshot(1L, entries);

        // 1. 指定前缀获取子树 Reader
        MapReader redisReader = snapshot.asReader("spring.redis");
        Assert.assertNotNull(redisReader);
        Assert.assertFalse(redisReader.isEmpty());

        // 2. 强类型读取与转换
        Assert.assertEquals("127.0.0.1", redisReader.getString("host"));
        Assert.assertEquals(Integer.valueOf(6379), redisReader.getInt("port", 3306));
        Assert.assertEquals(Duration.ofMillis(500), redisReader.getDuration("timeout"));
        Assert.assertTrue(redisReader.getBoolean("enabled", false));

        // 3. 别名回退
        Assert.assertEquals(Integer.valueOf(3), redisReader.getInt("maxAttempts", 1, "max-attempts"));

        // 4. 默认值兜底
        Assert.assertEquals("default-password", redisReader.getString("password", "default-password"));
        Assert.assertEquals(Integer.valueOf(10), redisReader.getInt("database", 10));

        // 5. 必填参数校验
        Assert.assertEquals("127.0.0.1", redisReader.requireString("host", "Redis host is required"));
        try {
            redisReader.requireString("missingKey", "Required config is missing");
            Assert.fail("Expected IllegalArgumentException for missing required key");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("Required config is missing"));
        }

        // 6. 支持带末尾点号的前缀
        MapReader redisReaderWithDot = snapshot.asReader("spring.redis.");
        Assert.assertEquals("127.0.0.1", redisReaderWithDot.getString("host"));

        // 7. 空前缀回退到根视图
        MapReader rootReader = snapshot.asReader("");
        Assert.assertTrue(rootReader.getReader("spring").containsKey("redis"));

        // 8. 不存在的子树返回空 Reader
        MapReader nonExistentReader = snapshot.asReader("non.existent");
        Assert.assertNotNull(nonExistentReader);
        Assert.assertTrue(nonExistentReader.isEmpty());
        Assert.assertEquals(Integer.valueOf(6379), nonExistentReader.getInt("port", 6379));

        // 9. 叶子节点非 Map 前缀返回空 Reader
        MapReader leafReader = snapshot.asReader("spring.redis.port");
        Assert.assertNotNull(leafReader);
        Assert.assertTrue(leafReader.isEmpty());
    }

    @Test
    public void testAsReaderToBean() {
        Map<String, ConfigEntry> entries = new HashMap<>();
        long now = System.currentTimeMillis();
        entries.put("db.url", new ConfigEntry("db.url", "jdbc:mysql://localhost:3306/demo", "src", now));
        entries.put("db.user-name", new ConfigEntry("db.user-name", "root", "src", now));
        entries.put("db.max-connections", new ConfigEntry("db.max-connections", "50", "src", now));
        entries.put("db.connect-timeout", new ConfigEntry("db.connect-timeout", "3s", "src", now));
        entries.put("db.auto-commit", new ConfigEntry("db.auto-commit", "true", "src", now));

        ConfigSnapshot snapshot = new ConfigSnapshot(1L, entries);
        DbProperties db = snapshot.asReader("db").toBean(DbProperties.class);

        Assert.assertNotNull(db);
        Assert.assertEquals("jdbc:mysql://localhost:3306/demo", db.getUrl());
        Assert.assertEquals("root", db.getUserName());
        Assert.assertEquals(50, db.getMaxConnections());
        Assert.assertEquals(Duration.ofSeconds(3), db.getConnectTimeout());
        Assert.assertTrue(db.isAutoCommit());
    }

    @lombok.Data
    public static class DbProperties {
        private String url;
        private String userName;
        private int maxConnections;
        private Duration connectTimeout;
        private boolean autoCommit;
    }
}
