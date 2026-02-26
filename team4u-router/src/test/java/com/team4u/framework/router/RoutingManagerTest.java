package com.team4u.framework.router;

import com.team4u.framework.config.core.internal.DefaultConfigManager;
import com.team4u.framework.config.core.spi.ConfigSourceRegistry;
import com.team4u.framework.config.core.spi.ConfigWatcherRegistry;
import com.team4u.framework.config.core.spi.InMemoryConfigSource;
import com.team4u.framework.criterion.Criteria;
import com.team4u.framework.router.api.RouteResult;
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
        String config = "{\"type\":\"map\", \"rules\":{\"test\":\"success\"}}";
        configSource.putAndRefresh(routerId, config);
        DefaultConfigManager.global().refresh();

        RouteResult<String> result = RoutingManager.global().route(routerId, "test");
        Assert.assertTrue(result.isMatch());
        Assert.assertEquals("success", result.getValue());
    }

    @Test
    public void testRouteExpression() {
        String routerId = "test.expression";
        String config = "{\"type\":\"expression\", \"rules\":{\"ok\":\"bingo\"}}";
        configSource.putAndRefresh(routerId, config);
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

        String config = "{\"type\":\"expression\", \"rules\":{\"name is_special true\":\"Matched\"}}";

        Map<String, Object> req = new HashMap<>();
        req.put("name", "special");
        RouteResult<String> result = routingManager.routeByConfig(config, req);
        Assert.assertTrue(result.isMatch());
        Assert.assertEquals("Matched", result.getValue());
    }

    @Test
    public void testCacheEffect() {
        String routerId = "test.cache";
        String config = "{\"type\":\"map\", \"rules\":{\"k\":\"v\"}}";
        configSource.putAndRefresh(routerId, config);
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
        String config = "{\"type\":\"unknown\", \"rules\":{}}";
        configSource.putAndRefresh(routerId, config);
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
        String config = "{\"type\":\"map\", \"rules\":{\"direct\":\"ok\"}}";
        RouteResult<String> result = RoutingManager.global().routeByConfig(config, "direct");
        Assert.assertTrue(result.isMatch());
        Assert.assertEquals("ok", result.getValue());
    }

    @Test
    public void testConfigNotFound() {
        RouteResult<String> result = RoutingManager.global().route("non.existent", "test");
        Assert.assertFalse(result.isMatch());
    }
}