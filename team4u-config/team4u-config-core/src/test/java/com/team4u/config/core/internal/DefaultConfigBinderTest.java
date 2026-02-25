package com.team4u.config.core.internal;

import com.team4u.config.core.domain.ConfigEntry;
import com.team4u.config.core.domain.ConfigSnapshot;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class DefaultConfigBinderTest {

    private DefaultConfigBinder binder;
    private ConfigSnapshot snapshot;

    @Before
    public void setUp() {
        binder = new DefaultConfigBinder();

        Map<String, ConfigEntry> entries = new HashMap<>();
        // 基本类型与占位符
        entries.put("app.port", new ConfigEntry("app.port", "8080", "s", 1L));
        entries.put("app.name", new ConfigEntry("app.name", "TestApp", "s", 1L));
        entries.put("app.desc", new ConfigEntry("app.desc", "Port is ${app.port}", "s", 1L));

        // 复杂 Bean 结构
        entries.put("server.host", new ConfigEntry("server.host", "localhost", "s", 1L));
        // 测试松散绑定: connect-timeout -> connectTimeout
        entries.put("server.connect-timeout", new ConfigEntry("server.connect-timeout", "5000", "s", 1L));
        entries.put("server.max_threads", new ConfigEntry("server.max_threads", "200", "s", 1L));
        // 嵌套属性
        entries.put("server.db.url", new ConfigEntry("server.db.url", "jdbc:mysql", "s", 1L));
        entries.put("server.db.username", new ConfigEntry("server.db.username", "root", "s", 1L));
        entries.put("server.db.password", new ConfigEntry("server.db.password", "123456", "s", 1L));

        snapshot = new ConfigSnapshot(1L, entries);
    }

    @Test
    public void testBindSimpleType() {
        Integer port = binder.bind(snapshot, "app.port", Integer.class);
        Assert.assertEquals(Integer.valueOf(8080), port);

        String name = binder.bind(snapshot, "app.name", String.class);
        Assert.assertEquals("TestApp", name);
    }

    @Test
    public void testBindSimpleTypeWithPlaceholder() {
        String desc = binder.bind(snapshot, "app.desc", String.class);
        Assert.assertEquals("Port is 8080", desc); // 占位符被解析
    }

    @Test
    public void testBindMap() {
        Map<String, Object> serverMap = binder.bind(snapshot, "server", Map.class);
        Assert.assertNotNull(serverMap);
        Assert.assertEquals("localhost", serverMap.get("host"));
        Assert.assertTrue(serverMap.get("db") instanceof Map);

        Map<String, Object> dbMap = (Map<String, Object>) serverMap.get("db");
        Assert.assertEquals("root", dbMap.get("username"));
    }

    @Test
    public void testBindJavaBean() {
        ServerConfig config = binder.bind(snapshot, "server", ServerConfig.class);

        Assert.assertNotNull(config);
        Assert.assertEquals("localhost", config.getHost());
        // 松散绑定生效
        Assert.assertEquals(5000, config.getConnectTimeout());
        Assert.assertEquals(200, config.getMaxThreads());

        Assert.assertNotNull(config.getDb());
        Assert.assertEquals("jdbc:mysql", config.getDb().getUrl());
        Assert.assertEquals("root", config.getDb().getUsername());
        Assert.assertEquals("123456", config.getDb().getPassword());
    }

    @Test
    public void testBindNonExistent() {
        Integer val = binder.bind(snapshot, "not.exist", Integer.class);
        Assert.assertNull(val); // 不存在的前缀返回 null

        ServerConfig config = binder.bind(snapshot, "not.exist", ServerConfig.class);
        Assert.assertNull(config);
    }

    // --- Test Beans ---
    public static class ServerConfig {
        private String host;
        private int connectTimeout;
        private int maxThreads;
        private DbConfig db;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(int connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public int getMaxThreads() {
            return maxThreads;
        }

        public void setMaxThreads(int maxThreads) {
            this.maxThreads = maxThreads;
        }

        public DbConfig getDb() {
            return db;
        }

        public void setDb(DbConfig db) {
            this.db = db;
        }
    }

    public static class DbConfig {
        private String url;
        private String username;
        private String password;

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

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}
