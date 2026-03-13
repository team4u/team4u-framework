package com.team4u.framework.config.core.internal;

import com.team4u.framework.config.core.domain.ConfigEntry;
import com.team4u.framework.config.core.domain.ConfigSnapshot;
import lombok.Data;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 默认配置绑定器测试类
 * 用于验证配置快照中的属性如何绑定到简单的 Java 类型、Map 或 JavaBean 对象
 */
public class DefaultConfigBinderTest {

    /**
     * 配置绑定器实例
     */
    private DefaultConfigBinder binder;

    /**
     * 配置快照实例，包含测试用的初始配置数据
     */
    private ConfigSnapshot snapshot;

    /**
     * 初始化测试环境，准备配置绑定器和包含各种类型属性的配置快照
     */
    @Before
    public void setUp() {
        binder = new DefaultConfigBinder();

        Map<String, ConfigEntry> entries = new HashMap<>();

        // 设置基本类型与占位符相关的配置项
        entries.put("app.port", new ConfigEntry("app.port", "8080", "s", 1L));
        entries.put("app.name", new ConfigEntry("app.name", "TestApp", "s", 1L));
        entries.put("app.desc", new ConfigEntry("app.desc", "Port is ${app.port}", "s", 1L));

        // 设置复杂 Bean 结构相关的配置项
        entries.put("server.host", new ConfigEntry("server.host", "localhost", "s", 1L));

        // 设置支持松散绑定的配置项（例如：横杠连字符形式）
        entries.put("server.connect-timeout", new ConfigEntry("server.connect-timeout", "5000", "s", 1L));
        // 设置支持松散绑定的配置项（例如：下划线形式）
        entries.put("server.max_threads", new ConfigEntry("server.max_threads", "200", "s", 1L));

        // 设置布尔值
        entries.put("server.enabled", new ConfigEntry("server.enabled", "true", "s", 1L));
        entries.put("server.debug", new ConfigEntry("server.debug", "yes", "s", 1L));

        // 设置数组（逗号分隔）
        entries.put("server.tags", new ConfigEntry("server.tags", "web,api,test", "s", 1L));

        // 设置列表（逗号分隔）
        entries.put("server.ports", new ConfigEntry("server.ports", "80,443,8080", "s", 1L));

        // 设置嵌套属性相关的配置项
        entries.put("server.db.url", new ConfigEntry("server.db.url", "jdbc:mysql", "s", 1L));
        entries.put("server.db.username", new ConfigEntry("server.db.username", "root", "s", 1L));
        entries.put("server.db.password", new ConfigEntry("server.db.password", "123456", "s", 1L));

        snapshot = new ConfigSnapshot(1L, entries);
    }

    /**
     * 测试将配置项绑定到基本 Java 类型（如 Integer, String）
     */
    @Test
    public void testBindSimpleType() {
        // 验证整数类型绑定
        Integer port = binder.bind(snapshot, "app.port", Integer.class);
        Assert.assertEquals(Integer.valueOf(8080), port);

        // 验证字符串类型绑定
        String name = binder.bind(snapshot, "app.name", String.class);
        Assert.assertEquals("TestApp", name);

        // 验证布尔类型绑定
        Boolean enabled = binder.bind(snapshot, "server.enabled", Boolean.class);
        Assert.assertTrue(enabled);

        // 验证布尔类型绑定（支持 yes/no 等特殊值）
        Boolean debug = binder.bind(snapshot, "server.debug", Boolean.class);
        Assert.assertTrue(debug);
    }

    /**
     * 测试将配置项绑定到复杂类型（如数组、列表）
     */
    @Test
    public void testBindComplexTypes() {
        // 验证数组类型绑定
        String[] tags = binder.bind(snapshot, "server.tags", String[].class);
        Assert.assertArrayEquals(new String[]{"web", "api", "test"}, tags);

        // 验证 JavaBean 中复杂类型的绑定
        ServerConfig config = binder.bind(snapshot, "server", ServerConfig.class);
        Assert.assertNotNull(config);
        Assert.assertTrue(config.isEnabled());
        Assert.assertTrue(config.isDebug());
        Assert.assertArrayEquals(new String[]{"web", "api", "test"}, config.getTags());

        Assert.assertNotNull(config.getPorts());
        Assert.assertEquals(3, config.getPorts().size());
        Assert.assertTrue(config.getPorts().get(0) instanceof Integer);
        Assert.assertEquals(Integer.valueOf(80), config.getPorts().get(0));
        Assert.assertEquals(Integer.valueOf(443), config.getPorts().get(1));
        Assert.assertEquals(Integer.valueOf(8080), config.getPorts().get(2));
    }

    /**
     * 测试包含占位符的配置项绑定，验证占位符是否能被正确解析
     */
    @Test
    public void testBindSimpleTypeWithPlaceholder() {
        // 占位符 ${app.port} 应该被解析为 8080
        String desc = binder.bind(snapshot, "app.desc", String.class);
        Assert.assertEquals("Port is 8080", desc);
    }

    /**
     * 测试将配置项绑定到 Map 结构
     */
    @Test
    public void testBindMap() {
        // 绑定以 server 为前缀的所有配置到 Map
        Map<String, Object> serverMap = binder.bind(snapshot, "server", Map.class);
        Assert.assertNotNull(serverMap);
        Assert.assertEquals("localhost", serverMap.get("host"));
        Assert.assertTrue(serverMap.get("db") instanceof Map);

        // 验证嵌套的 Map 结构
        Map<String, Object> dbMap = (Map<String, Object>) serverMap.get("db");
        Assert.assertEquals("root", dbMap.get("username"));
    }

    /**
     * 测试将配置项绑定到自定义的 JavaBean 对象，验证复杂结构和松散绑定支持
     */
    @Test
    public void testBindJavaBean() {
        // 绑定以 server 为前缀的所有配置到 ServerConfig 对象
        ServerConfig config = binder.bind(snapshot, "server", ServerConfig.class);

        Assert.assertNotNull(config);
        Assert.assertEquals("localhost", config.getHost());

        // 验证松散绑定是否生效（connect-timeout 绑定到 connectTimeout）
        Assert.assertEquals(5000, config.getConnectTimeout());
        // 验证松散绑定是否生效（max_threads 绑定到 maxThreads）
        Assert.assertEquals(200, config.getMaxThreads());

        // 验证嵌套对象是否被正确填充
        Assert.assertNotNull(config.getDb());
        Assert.assertEquals("jdbc:mysql", config.getDb().getUrl());
        Assert.assertEquals("root", config.getDb().getUsername());
        Assert.assertEquals("123456", config.getDb().getPassword());
    }

    /**
     * 测试绑定不存在的配置前缀，验证系统鲁棒性
     */
    @Test
    public void testBindNonExistent() {
        // 不存在的前缀应返回 null
        Integer val = binder.bind(snapshot, "not.exist", Integer.class);
        Assert.assertNull(val);

        // 不存在的前缀绑定到 Bean 也应返回 null
        ServerConfig config = binder.bind(snapshot, "not.exist", ServerConfig.class);
        Assert.assertNull(config);
    }

    /**
     * 服务器配置类，用于测试配置绑定
     */
    @Data
    public static class ServerConfig {
        /**
         * 是否启用
         */
        private boolean enabled;
        /**
         * 是否开启调试
         */
        private boolean debug;
        /**
         * 标签列表
         */
        private String[] tags;
        /**
         * 端口列表
         */
        private List<Integer> ports;
        /**
         * 服务器主机地址
         */
        private String host;
        /**
         * 连接超时时间（毫秒）
         */
        private int connectTimeout;
        /**
         * 最大线程数
         */
        private int maxThreads;
        /**
         * 数据库配置信息
         */
        private DbConfig db;
    }

    /**
     * 数据库配置类，用于测试嵌套配置绑定
     */
    @Data
    public static class DbConfig {
        /**
         * 数据库连接 URL
         */
        private String url;
        /**
         * 数据库用户名
         */
        private String username;
        /**
         * 数据库密码
         */
        private String password;
    }
}
