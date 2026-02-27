package com.team4u.framework.router;

import com.team4u.framework.config.core.internal.DefaultConfigManager;
import com.team4u.framework.config.core.spi.ConfigSourceRegistry;
import com.team4u.framework.config.core.spi.ConfigWatcherRegistry;
import com.team4u.framework.config.core.spi.InMemoryConfigSource;
import com.team4u.framework.criterion.Criteria;
import com.team4u.framework.router.api.model.RouteResult;
import com.team4u.framework.router.factory.ExpressionRouterFactory;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * RoutingManager 单元测试
 */
public class RoutingManagerTest {

    private static final InMemoryConfigSource configSource = new InMemoryConfigSource("test", 100);

    @BeforeClass
    public static void setUp() {
        // 注册内存配置源到全局注册表
        ConfigSourceRegistry.global().register(configSource);
        ConfigWatcherRegistry.global().register(configSource);
        // 强制刷新全局配置管理器以加载新配置源
        DefaultConfigManager.global().refresh();
    }

    @Test
    public void testRouteMap() {
        String routerId = "test.map";
        String config = "{\"type\":\"map\", \"rules\":[{\"condition\":\"test\", \"value\":\"success\"}]}";
        configSource.putAndRefresh("router." + routerId, config);
        DefaultConfigManager.global().refresh();

        RouteResult<String> result = RoutingManager.global().route(routerId, "test");
        Assert.assertTrue(result.isMatch());
        Assert.assertEquals("success", result.getValue());
    }

    @Test
    public void testRouteExpression() {
        String routerId = "test.expression";
        String config = "{\"type\":\"expression\", \"rules\":[{\"condition\":\"ok\", \"value\":\"bingo\"}]}";
        configSource.putAndRefresh("router." + routerId, config);
        DefaultConfigManager.global().refresh();

        RouteResult<String> result = RoutingManager.global().route(routerId, "ok");
        Assert.assertTrue(result.isMatch());
        Assert.assertEquals("bingo", result.getValue());
    }

    @Test
    public void testCustomExpressionCriteria() {
        Criteria criteria = Criteria.builder()
                .addOperator("is_special", (actual, expected) -> "special".equals(actual))
                .build();

        RoutingManager routingManager = RoutingManager.builder()
                .addFactory(new ExpressionRouterFactory(criteria))
                .build();

        String config = "{\"type\":\"expression\", \"rules\":[{\"condition\":\"name is_special true\", \"value\":\"Matched\"}]}";

        Map<String, Object> req = new HashMap<>();
        req.put("name", "special");
        RouteResult<String> result = routingManager.routeByConfig(config, req);
        Assert.assertTrue(result.isMatch());
        Assert.assertEquals("Matched", result.getValue());
    }

    @Test
    public void testCacheEffect() {
        String routerId = "test.cache";
        String config = "{\"type\":\"map\", \"rules\":[{\"condition\":\"k\", \"value\":\"v\"}]}";
        configSource.putAndRefresh("router." + routerId, config);
        DefaultConfigManager.global().refresh();

        RoutingManager manager = RoutingManager.global();

        // 第一次路由，触发解析与工厂创建
        manager.route(routerId, "k");

        // 验证结果
        RouteResult<String> result = manager.route(routerId, "k");
        Assert.assertEquals("v", result.getValue());
    }

    @Test
    public void testUnsupportedType() {
        String routerId = "test.unknown";
        String config = "{\"type\":\"unknown\", \"rules\":[]}";
        configSource.putAndRefresh("router." + routerId, config);
        DefaultConfigManager.global().refresh();

        try {
            RoutingManager.global().route(routerId, "test");
            Assert.fail("Should throw exception for unknown type");
        } catch (Exception e) {
            Assert.assertTrue(e.getMessage().contains("Unsupported router type"));
        }
    }

    @Test
    public void testRouteByConfig() {
        String config = "{\"type\":\"map\", \"rules\":[{\"condition\":\"direct\", \"value\":\"ok\"}]}";
        RouteResult<String> result = RoutingManager.global().routeByConfig(config, "direct");
        Assert.assertTrue(result.isMatch());
        Assert.assertEquals("ok", result.getValue());
    }

    @Test
    public void testConfigNotFound() {
        RouteResult<String> result = RoutingManager.global().route("non.existent", "test");
        Assert.assertFalse(result.isMatch());
    }

    @Test
    public void testInvalidConfig() {
        String config = "{\"type\":\"map\""; // 错误的 JSON
        try {
            RoutingManager.global().routeByConfig(config, "test");
            Assert.fail("Should throw exception for invalid config");
        } catch (Exception e) {
            // 可能是由于 JSON 解析失败或者 buildRouterFromConfig 抛出的异常
            Assert.assertNotNull(e.getMessage());
        }
    }

    @Test
    public void testTypeConversion() {
        String config = "{\"type\":\"map\", \"rules\":[{\"condition\":\"serviceA\", \"value\":{\"host\":\"127.0.0.1\",\"port\":8080}}]}";
        RouteResult<TargetService> result = RoutingManager.global().routeByConfig(config, "serviceA",
                TargetService.class);

        Assert.assertTrue(result.isMatch());
        Assert.assertNotNull(result.getValue());
        Assert.assertEquals("127.0.0.1", result.getValue().getHost());
        Assert.assertEquals(Integer.valueOf(8080), result.getValue().getPort());
    }

    public static class TargetService {
        private String host;
        private Integer port;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public Integer getPort() {
            return port;
        }

        public void setPort(Integer port) {
            this.port = port;
        }
    }
}