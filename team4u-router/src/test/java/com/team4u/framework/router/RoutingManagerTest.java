package com.team4u.framework.router;

import com.team4u.framework.config.test.TestConfigContext;
import com.team4u.framework.criterion.Criteria;
import com.team4u.framework.router.api.builder.RoutePolicyBuilder;
import com.team4u.framework.router.api.model.RoutePolicy;
import com.team4u.framework.router.api.model.RouteResult;
import com.team4u.framework.router.factory.ExpressionRouterFactory;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * RoutingManager 单元测试
 */
public class RoutingManagerTest {

    private TestConfigContext configContext;
    private RoutingManager routingManager;

    @Before
    public void setUp() {
        configContext = TestConfigContext.create();
        routingManager = RoutingManager.builder()
                .configManager(configContext.getManager())
                .build();
    }

    @Test
    public void testRouteMap() {
        String routerId = "test.map";
        String config = "{\"type\":\"map\", \"rules\":[{\"condition\":\"test\", \"value\":\"success\"}]}";
        configContext.put("router." + routerId, config);

        RouteResult<String> result = routingManager.route(routerId, "test");
        Assert.assertTrue(result.isMatch());
        Assert.assertEquals("success", result.getValue());
    }

    @Test
    public void testRouteExpression() {
        String routerId = "test.expression";
        String config = "{\"type\":\"expression\", \"rules\":[{\"condition\":\"ok\", \"value\":\"bingo\"}]}";
        configContext.put("router." + routerId, config);

        RouteResult<String> result = routingManager.route(routerId, "ok");
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
        configContext.put("router." + routerId, config);

        // 第一次路由，触发解析与工厂创建
        routingManager.route(routerId, "k");

        // 验证结果
        RouteResult<String> result = routingManager.route(routerId, "k");
        Assert.assertEquals("v", result.getValue());
    }

    @Test
    public void testUnsupportedType() {
        String routerId = "test.unknown";
        String config = "{\"type\":\"unknown\", \"rules\":[]}";
        configContext.put("router." + routerId, config);

        try {
            routingManager.route(routerId, "test");
            Assert.fail("Should throw exception for unknown type");
        } catch (Exception e) {
            Assert.assertTrue(e.getMessage().contains("Unsupported router type"));
        }
    }

    @Test
    public void testRouteByConfig() {
        String config = "{\"type\":\"map\", \"rules\":[{\"condition\":\"direct\", \"value\":\"ok\"}]}";
        RouteResult<String> result = routingManager.routeByConfig(config, "direct");
        Assert.assertTrue(result.isMatch());
        Assert.assertEquals("ok", result.getValue());
    }

    @Test
    public void testConfigNotFound() {
        RouteResult<String> result = routingManager.route("non.existent", "test");
        Assert.assertFalse(result.isMatch());
    }

    @Test
    public void testInvalidConfig() {
        String config = "{\"type\":\"map\""; // 错误的 JSON
        try {
            routingManager.routeByConfig(config, "test");
            Assert.fail("Should throw exception for invalid config");
        } catch (Exception e) {
            // 可能是由于 JSON 解析失败或者 buildRouterFromConfig 抛出的异常
            Assert.assertNotNull(e.getMessage());
        }
    }

    @Test
    public void testTypeConversion() {
        String config = "{\"type\":\"map\", \"rules\":[{\"condition\":\"serviceA\", \"value\":{\"host\":\"127.0.0.1\",\"port\":8080}}]}";
        RouteResult<TargetService> result = routingManager.routeByConfig(config, "serviceA",
                TargetService.class);

        Assert.assertTrue(result.isMatch());
        Assert.assertNotNull(result.getValue());
        Assert.assertEquals("127.0.0.1", result.getValue().getHost());
        Assert.assertEquals(Integer.valueOf(8080), result.getValue().getPort());
    }

    @Test
    public void testMapRouterWithBuilder() {
        // 使用强类型的构建器创建路由策略
        RoutePolicy policy = RoutePolicyBuilder.<String>map()
                .id("pay_channel_router")
                .rule("ALIPAY", "AlipayService")
                .rule("WECHAT", "WechatPayService")
                .fallback("CashService")
                .build();

        // 执行路由并验证结果
        Assert.assertEquals("AlipayService", routingManager.routeByPolicy(policy, "ALIPAY").getValue());
        Assert.assertEquals("CashService", routingManager.routeByPolicy(policy, "UNKNOWN_PAY").getValue());
    }

    @Test
    public void testExpressionRouterWithBuilder() {
        // 使用强类型的构建器创建表达式路由策略
        RoutePolicy policy = RoutePolicyBuilder.<String>expression()
                .id("grpc_gray_router")
                .rule("isVip == true", "grpc://vip-cluster:8080")
                .fallback("grpc://main-cluster:8080")
                .build();

        // 执行路由并验证结果
        Map<String, Object> vipReq = new HashMap<>();
        vipReq.put("isVip", true);
        Assert.assertEquals("grpc://vip-cluster:8080", routingManager.routeByPolicy(policy, vipReq).getValue());

        Map<String, Object> normalReq = new HashMap<>();
        normalReq.put("isVip", false);
        Assert.assertEquals("grpc://main-cluster:8080", routingManager.routeByPolicy(policy, normalReq).getValue());
    }

    @Test
    public void testCustomConfigPrefix() {
        String customPrefix = "biz.router.";
        RoutingManager customManager = RoutingManager.builder()
                .configManager(configContext.getManager())
                .configPrefix(customPrefix)
                .build();

        String routerId = "order";
        String config = "{\"type\":\"map\", \"rules\":[{\"condition\":\"CN\", \"value\":\"china\"}]}";
        // 使用自定义前缀存储配置
        configContext.put(customPrefix + routerId, config);

        RouteResult<String> result = customManager.route(routerId, "CN");
        Assert.assertTrue(result.isMatch());
        Assert.assertEquals("china", result.getValue());
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