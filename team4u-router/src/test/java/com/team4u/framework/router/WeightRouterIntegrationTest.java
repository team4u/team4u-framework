package com.team4u.framework.router;

import com.team4u.framework.router.api.builder.RoutePolicyBuilder;
import com.team4u.framework.router.api.model.RoutePolicy;
import com.team4u.framework.router.api.model.RouteResult;
import com.team4u.framework.router.api.model.RouteRule;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

/**
 * WeightRouter 工厂发现与集成测试
 *
 * @author jay.wu
 */
public class WeightRouterIntegrationTest {

    @Test
    public void testRoutingManagerWithWeightType() {
        RoutePolicy policy = new RoutePolicy();
        policy.setType("weight");
        policy.setRules(Arrays.asList(
                new RouteRule("1", "A"),
                new RouteRule("0", "B")));

        // 通过 RoutingManager 构建路由器，以此验证 SPI 发现是否生效
        RouteResult<String> result = RoutingManager.global().routeByPolicy(policy, "any");

        Assert.assertTrue("应当匹配成功", result.isMatch());
        Assert.assertEquals("应当命中唯一权重为 1 的规则", "A", result.getValue());
    }

    @Test
    public void testRoutingManagerWithBuilder() {
        RoutePolicy policy = RoutePolicyBuilder.<String>weight()
                .rule("1", "A")
                .rule("0", "B")
                .build();

        RouteResult<String> result = RoutingManager.global().routeByPolicy(policy, "any");

        Assert.assertTrue("应当匹配成功", result.isMatch());
        Assert.assertEquals("应当命中唯一权重为 1 的规则", "A", result.getValue());
    }
}
