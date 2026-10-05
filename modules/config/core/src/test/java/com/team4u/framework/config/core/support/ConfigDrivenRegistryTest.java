package com.team4u.framework.config.core.support;

import com.team4u.framework.base.util.MapReader;
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.spi.InMemoryConfigSource;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ConfigDrivenRegistry 单元测试
 */
public class ConfigDrivenRegistryTest {

    private InMemoryConfigSource configSource;
    private ConfigManager configManager;

    @Before
    public void setUp() {
        configSource = new InMemoryConfigSource("test", 100);
        configManager = ConfigManager.builder()
                .addSource(configSource)
                .addWatcher(configSource)
                .debounceWindow(0)
                .build();
    }

    @Test
    public void testLazyLoad() {
        ConfigDrivenRegistry<String> registry = ConfigDrivenRegistry.forKeys(
                configManager, "test.*", s -> s.toUpperCase());

        configSource.putAndRefresh("test.k1", "v1");

        // 验证延迟初始化逻辑
        Assert.assertEquals("V1", registry.get("test.k1"));
    }

    @Test
    public void testHotReload() {
        ConfigDrivenRegistry<String> registry = ConfigDrivenRegistry.forKeys(
                configManager, "test.*", s -> s.toUpperCase());

        configSource.putAndRefresh("test.k1", "v1");
        Assert.assertEquals("V1", registry.get("test.k1"));

        // 更新配置
        configSource.putAndRefresh("test.k1", "v2");
        // 验证自动刷新逻辑
        Assert.assertEquals("V2", registry.get("test.k1"));
    }

    @Test
    public void testSingleKeyModeAndNoArgGet() {
        String configKey = "team4u.log.finops";
        ConfigDrivenRegistry<String> registry = ConfigDrivenRegistry.forKey(
                configManager, configKey, s -> s.toUpperCase());

        Assert.assertTrue(registry.defaultInstanceKey().isPresent());
        Assert.assertEquals(configKey, registry.getKeyPrefix());

        configSource.putAndRefresh(configKey, "v1");
        // 初次加载：无参 get() 与有参 get(configKey)
        Assert.assertEquals("V1", registry.get());
        Assert.assertEquals("V1", registry.get(configKey));

        // 更新配置：验证热重载
        configSource.putAndRefresh(configKey, "v2");
        Assert.assertEquals("V2", registry.get());

        // 单 Key 模式下，同前缀的其他 Key 变更不会误触
        configSource.putAndRefresh("team4u.log.finops_extra", "v3");
        Assert.assertEquals("V2", registry.get());

        // 验证带配置键的 BiFunction 重载
        ConfigDrivenRegistry<String> biRegistry = ConfigDrivenRegistry.forKey(
                configManager, configKey, (key, value) -> key + ":" + value.toUpperCase());
        Assert.assertEquals(configKey + ":V2", biRegistry.get());
    }

    @Test
    public void testExactKeyWithoutWildcardDoesNotAutoAppendDot() {
        // 验证传入 "clients" 时作为精确 Key，不自作主张添加 "." 或 "*"
        ConfigDrivenRegistry<String> registry = ConfigDrivenRegistry.forKey(
                configManager, "clients", s -> s.toUpperCase());

        Assert.assertTrue(registry.defaultInstanceKey().isPresent());
        Assert.assertEquals("clients", registry.getKeyPrefix());

        configSource.putAndRefresh("clients", "v1");
        Assert.assertEquals("V1", registry.get());

        // 验证对 clients.sms 等子项无感知
        configSource.putAndRefresh("clients.sms", "v2");
        Assert.assertEquals("V1", registry.get());
    }

    @Test
    public void testPatternModeSubKeyResolution() {
        ConfigDrivenRegistry<String> registry = ConfigDrivenRegistry.forKeys(
                configManager, "test.*", s -> s.toUpperCase());

        Assert.assertFalse(registry.defaultInstanceKey().isPresent());
        Assert.assertEquals("test.", registry.getKeyPrefix());

        configSource.putAndRefresh("test.k1", "v1");

        // 短标识与完整 Key 均能解析并命中同一实例
        Assert.assertEquals("V1", registry.get("k1"));
        Assert.assertEquals("V1", registry.get("test.k1"));

        // 更新配置后，通过短标识也能读到最新值
        configSource.putAndRefresh("test.k1", "v2");
        Assert.assertEquals("V2", registry.get("k1"));

        // 验证带配置键的 BiFunction 重载
        ConfigDrivenRegistry<String> biRegistry = ConfigDrivenRegistry.forKeys(
                configManager, "test.*", (key, value) -> key + ":" + value.toUpperCase());
        Assert.assertEquals("test.k1:V2", biRegistry.get("k1"));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testPatternModeNoArgGetThrowsException() {
        ConfigDrivenRegistry<String> registry = ConfigDrivenRegistry.forKeys(
                configManager, "test.*", s -> s.toUpperCase());
        registry.get();
    }

    @Test
    public void testSafeSwap() {
        ConfigDrivenRegistry<String> registry = ConfigDrivenRegistry.forKeys(
                configManager, "test.*", val -> {
            if ("error".equals(val)) {
                throw new RuntimeException("Invalid config");
            }
            return val.toUpperCase();
        });

        configSource.putAndRefresh("test.k1", "v1");
        Assert.assertEquals("V1", registry.get("test.k1"));

        // 模拟配置异常
        configSource.putAndRefresh("test.k1", "error");
        // 异常时应保留历史版本实例
        Assert.assertEquals("V1", registry.get("test.k1"));

        // 恢复正常配置
        configSource.putAndRefresh("test.k1", "v2");
        Assert.assertEquals("V2", registry.get("test.k1"));
    }

    @Test
    public void testGracefulShutdown() throws Exception {
        AtomicInteger closeCount = new AtomicInteger(0);

        ConfigDrivenRegistry<MockInstance> registry = ConfigDrivenRegistry.forKeys(
                configManager, "test.*", name -> new MockInstance(name, closeCount));

        configSource.putAndRefresh("test.k1", "i1");
        MockInstance i1 = registry.get("test.k1");
        Assert.assertEquals("i1", i1.toString());

        // 执行实例替换
        configSource.putAndRefresh("test.k1", "i2");
        Assert.assertEquals("i2", registry.get("test.k1").toString());

        // 验证历史实例资源已回收
        Assert.assertEquals(1, closeCount.get());

        // 执行配置删除
        configSource.putAndRefresh("test.k1", null);
        Assert.assertNull(registry.get("test.k1"));
        // 验证实例资源已回收
        Assert.assertEquals(2, closeCount.get());
    }

    @Test
    public void testDestroyUnregistersListener() {
        AtomicInteger factoryCount = new AtomicInteger(0);

        ConfigDrivenRegistry<String> registry = ConfigDrivenRegistry.forKeys(
                configManager, "test.*", value -> {
            factoryCount.incrementAndGet();
            return value.toUpperCase();
        });

        configSource.putAndRefresh("test.k1", "v1");
        Assert.assertEquals("V1", registry.get("test.k1"));
        Assert.assertEquals(1, factoryCount.get());

        registry.destroy();

        configSource.putAndRefresh("test.k1", "v2");
        Assert.assertEquals(1, factoryCount.get());
    }

    // -----------------------------------------------------------------------
    // 前缀模式（扁平 Key 树驱动）单元测试
    // -----------------------------------------------------------------------

    @Test
    public void testPrefixSingleInstanceLazyAndHotReload() {
        AtomicInteger buildCount = new AtomicInteger(0);
        AtomicInteger closeCount = new AtomicInteger(0);

        ConfigDrivenRegistry<PrefixMockServer> registry = ConfigDrivenRegistry.forPrefix(
                configManager, "server", reader -> {
                    buildCount.incrementAndGet();
                    return new PrefixMockServer(reader, closeCount);
                });

        Assert.assertTrue(registry.defaultInstanceKey().isPresent());
        Assert.assertEquals("server", registry.getKeyPrefix());
        Assert.assertEquals("server.*", registry.getKeyOrPattern());

        // 初始未配置时，get() 延迟加载返回 null
        Assert.assertNull(registry.get());
        Assert.assertEquals(0, buildCount.get());

        // 写入初始扁平配置
        configSource.put("server.host", "localhost");
        configSource.putAndRefresh("server.port", "8080");

        PrefixMockServer server1 = registry.get();
        Assert.assertNotNull(server1);
        Assert.assertEquals("localhost", server1.host);
        Assert.assertEquals(8080, server1.port);
        Assert.assertEquals(1, buildCount.get());

        // 重复读取命中缓存，不重复执行构建
        Assert.assertSame(server1, registry.get());
        Assert.assertSame(server1, registry.get("server"));
        Assert.assertEquals(1, buildCount.get());

        // 任一叶子键变更 -> 触发热重建
        configSource.putAndRefresh("server.port", "8081");
        PrefixMockServer server2 = registry.get();
        Assert.assertNotNull(server2);
        Assert.assertNotSame(server1, server2);
        Assert.assertEquals(8081, server2.port);
        Assert.assertEquals("localhost", server2.host);
        Assert.assertEquals(2, buildCount.get());
        Assert.assertEquals(1, closeCount.get());

        // 子树内容未变的重载不会触发重建（构建计数器不变）
        configSource.putAndRefresh("server.port", "8081");
        Assert.assertSame(server2, registry.get());
        Assert.assertEquals(2, buildCount.get());
    }

    @Test
    public void testPrefixBatchChangesFingerprintDedup() {
        AtomicInteger buildCount = new AtomicInteger(0);
        ConfigDrivenRegistry<PrefixMockServer> registry = ConfigDrivenRegistry.forPrefix(
                configManager, "server", reader -> {
                    buildCount.incrementAndGet();
                    return new PrefixMockServer(reader, null);
                });

        // 初始配置
        configSource.put("server.host", "localhost");
        configSource.putAndRefresh("server.port", "8080");
        Assert.assertNotNull(registry.get());
        Assert.assertEquals(1, buildCount.get());

        // 单次重载中批量修改同一实例下的多个扁平键
        Map<String, String> batch = new HashMap<>();
        batch.put("server.host", "127.0.0.1");
        batch.put("server.port", "9090");
        batch.put("server.timeout", "3000");
        configSource.putAllAndRefresh(batch);

        // 指纹去重保证合并多键变更，仅构建一次新实例
        Assert.assertEquals(2, buildCount.get());
        PrefixMockServer server = registry.get();
        Assert.assertEquals("127.0.0.1", server.host);
        Assert.assertEquals(9090, server.port);
    }

    @Test
    public void testPrefixMultiInstanceIndependence() {
        AtomicInteger smsBuildCount = new AtomicInteger(0);
        AtomicInteger payBuildCount = new AtomicInteger(0);
        AtomicInteger smsCloseCount = new AtomicInteger(0);
        AtomicInteger payCloseCount = new AtomicInteger(0);

        ConfigDrivenRegistry<PrefixMockClient> registry = ConfigDrivenRegistry.forPrefixes(
                configManager, "clients", (id, reader) -> {
                    if ("sms".equals(id)) {
                        smsBuildCount.incrementAndGet();
                        return new PrefixMockClient(id, reader, smsCloseCount);
                    } else if ("pay".equals(id)) {
                        payBuildCount.incrementAndGet();
                        return new PrefixMockClient(id, reader, payCloseCount);
                    }
                    return new PrefixMockClient(id, reader, null);
                });

        Assert.assertFalse(registry.defaultInstanceKey().isPresent());
        Assert.assertEquals("clients.", registry.getKeyPrefix());
        Assert.assertEquals("clients.*", registry.getKeyOrPattern());

        // 初始化两个子实例扁平配置
        configSource.put("clients.sms.endpoint", "https://sms.aliyun.com");
        configSource.put("clients.sms.timeout", "5000");
        configSource.put("clients.pay.endpoint", "https://pay.alipay.com");
        configSource.putAndRefresh("clients.pay.timeout", "3000");

        // 短标识与完整键均能命中同一子实例
        PrefixMockClient sms1 = registry.get("sms");
        Assert.assertSame(sms1, registry.get("clients.sms"));
        Assert.assertEquals("https://sms.aliyun.com", sms1.endpoint);
        Assert.assertEquals(5000, sms1.timeout);

        PrefixMockClient pay1 = registry.get("pay");
        Assert.assertSame(pay1, registry.get("clients.pay"));
        Assert.assertEquals("https://pay.alipay.com", pay1.endpoint);
        Assert.assertEquals(3000, pay1.timeout);

        Assert.assertEquals(1, smsBuildCount.get());
        Assert.assertEquals(1, payBuildCount.get());

        // 修改 clients.sms.* 配置，仅重建 sms 实例，clients.pay 实例完全不受影响
        configSource.putAndRefresh("clients.sms.timeout", "6000");

        PrefixMockClient sms2 = registry.get("sms");
        Assert.assertNotSame(sms1, sms2);
        Assert.assertEquals(6000, sms2.timeout);
        Assert.assertEquals(2, smsBuildCount.get());
        Assert.assertEquals(1, smsCloseCount.get());

        // pay 实例保持同一对象，未被重建且未被关闭
        PrefixMockClient pay2 = registry.get("pay");
        Assert.assertSame(pay1, pay2);
        Assert.assertEquals(1, payBuildCount.get());
        Assert.assertEquals(0, payCloseCount.get());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testPrefixMultiInstanceNoArgGetThrowsException() {
        ConfigDrivenRegistry<PrefixMockClient> registry = ConfigDrivenRegistry.forPrefixes(
                configManager, "clients", PrefixMockClient::new);
        registry.get();
    }

    @Test
    public void testPrefixHierarchicalDeletion() {
        AtomicInteger closeCount = new AtomicInteger(0);

        ConfigDrivenRegistry<PrefixMockServer> registry = ConfigDrivenRegistry.forPrefix(
                configManager, "server", reader -> new PrefixMockServer(reader, closeCount));

        configSource.put("server.host", "localhost");
        configSource.putAndRefresh("server.port", "8080");

        PrefixMockServer server1 = registry.get();
        Assert.assertEquals(8080, server1.port);
        Assert.assertEquals("localhost", server1.host);

        // 1. 删除单个叶子键 (server.port)，server.host 依然有效 -> 视为子树内容变化，正常热重建
        configSource.putAndRefresh("server.port", null);
        PrefixMockServer server2 = registry.get();
        Assert.assertNotNull(server2);
        Assert.assertNotSame(server1, server2);
        Assert.assertEquals(0, server2.port); // 缺失端口回退为默认 0
        Assert.assertEquals("localhost", server2.host);
        Assert.assertEquals(1, closeCount.get());

        // 2. 删光整个前缀下所有键 (server.host 置空) -> 实例被移除且旧实例 close 被调用
        configSource.putAndRefresh("server.host", null);
        Assert.assertNull(registry.get());
        Assert.assertEquals(2, closeCount.get());
    }

    @Test
    public void testPrefixBuildFailureKeepsOldInstance() {
        AtomicInteger closeCount = new AtomicInteger(0);

        ConfigDrivenRegistry<PrefixMockServer> registry = ConfigDrivenRegistry.forPrefix(
                configManager, "server", reader -> new PrefixMockServer(reader, closeCount));

        configSource.put("server.host", "localhost");
        configSource.putAndRefresh("server.port", "8080");

        PrefixMockServer server1 = registry.get();
        Assert.assertEquals(8080, server1.port);

        // 传入非法配置导致工厂抛出异常 (port < 0)
        configSource.putAndRefresh("server.port", "-1");

        // 构建失败应继续保留旧实例对外服务，且旧实例 close 未被调用
        PrefixMockServer serverAfterError = registry.get();
        Assert.assertSame(server1, serverAfterError);
        Assert.assertEquals(0, closeCount.get());

        // 恢复正确配置后正常热更新，旧实例被关闭
        configSource.putAndRefresh("server.port", "8082");
        PrefixMockServer server2 = registry.get();
        Assert.assertNotSame(server1, server2);
        Assert.assertEquals(8082, server2.port);
        Assert.assertEquals(1, closeCount.get());
    }

    @Test
    public void testPrefixMultiInstanceDeletion() {
        AtomicInteger smsClose = new AtomicInteger(0);
        AtomicInteger payClose = new AtomicInteger(0);

        ConfigDrivenRegistry<PrefixMockClient> registry = ConfigDrivenRegistry.forPrefixes(
                configManager, "clients", (id, reader) -> {
                    if ("sms".equals(id)) {
                        return new PrefixMockClient(id, reader, smsClose);
                    }
                    return new PrefixMockClient(id, reader, payClose);
                });

        configSource.put("clients.sms.endpoint", "https://sms.aliyun.com");
        configSource.put("clients.pay.endpoint", "https://pay.alipay.com");
        configSource.fireChange();

        Assert.assertNotNull(registry.get("sms"));
        Assert.assertNotNull(registry.get("pay"));

        // 删除 sms 的全部键
        configSource.putAndRefresh("clients.sms.endpoint", null);

        // sms 实例被移除并关闭，pay 实例不受影响
        Assert.assertNull(registry.get("sms"));
        Assert.assertEquals(1, smsClose.get());

        Assert.assertNotNull(registry.get("pay"));
        Assert.assertEquals(0, payClose.get());
    }

    @Test
    public void testPrefixWithTrailingDot() {
        ConfigDrivenRegistry<PrefixMockServer> registry = ConfigDrivenRegistry.forPrefix(
                configManager, "server.", reader -> new PrefixMockServer(reader));

        Assert.assertEquals("server", registry.getKeyPrefix());
        Assert.assertEquals("server.*", registry.getKeyOrPattern());

        configSource.putAndRefresh("server.port", "8080");
        Assert.assertEquals(8080, registry.get().port);
    }

    @Test
    public void testPrefixDestroyCleansAll() {
        AtomicInteger closeCount = new AtomicInteger(0);
        ConfigDrivenRegistry<PrefixMockServer> registry = ConfigDrivenRegistry.forPrefix(
                configManager, "server", reader -> new PrefixMockServer(reader, closeCount));

        configSource.putAndRefresh("server.port", "8080");
        Assert.assertNotNull(registry.get());

        registry.destroy();
        Assert.assertEquals(1, closeCount.get());

        // 销毁后变更不再生效
        configSource.putAndRefresh("server.port", "8081");
        Assert.assertEquals(1, closeCount.get());
    }

    private static class MockInstance implements AutoCloseable {
        private final String name;
        private final AtomicInteger closeCount;

        MockInstance(String name, AtomicInteger closeCount) {
            this.name = name;
            this.closeCount = closeCount;
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static class PrefixMockServer implements AutoCloseable {
        private final String host;
        private final int port;
        private final AtomicInteger closeCount;

        PrefixMockServer(MapReader reader) {
            this(reader, null);
        }

        PrefixMockServer(MapReader reader, AtomicInteger closeCount) {
            this.host = reader.getString("host");
            this.port = reader.getInt("port", 0);
            if (this.port < 0) {
                throw new IllegalArgumentException("Invalid port: " + this.port);
            }
            this.closeCount = closeCount;
        }

        @Override
        public void close() {
            if (closeCount != null) {
                closeCount.incrementAndGet();
            }
        }
    }

    private static class PrefixMockClient implements AutoCloseable {
        private final String id;
        private final String endpoint;
        private final int timeout;
        private final AtomicInteger closeCount;

        PrefixMockClient(String id, MapReader reader) {
            this(id, reader, null);
        }

        PrefixMockClient(String id, MapReader reader, AtomicInteger closeCount) {
            this.id = id;
            this.endpoint = reader.getString("endpoint");
            this.timeout = reader.getInt("timeout", 1000);
            this.closeCount = closeCount;
        }

        @Override
        public void close() {
            if (closeCount != null) {
                closeCount.incrementAndGet();
            }
        }
    }
}
