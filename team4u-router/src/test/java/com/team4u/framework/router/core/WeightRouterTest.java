package com.team4u.framework.router.core;

import com.team4u.framework.router.api.exception.RouteConfigException;
import com.team4u.framework.router.api.model.RoutePolicy;
import com.team4u.framework.router.api.model.RouteResult;
import com.team4u.framework.router.api.model.RouteRule;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 权重路由器单元测试
 *
 * @author jay.wu
 */
public class WeightRouterTest {

    /**
     * 测试权重分布是否大致符合预期
     */
    @Test
    public void testWeightRoutingDistribution() {
        RoutePolicy policy = new RoutePolicy();
        policy.setType("weight");
        policy.setRules(Arrays.asList(
                new RouteRule("20", "A"),
                new RouteRule("30", "B"),
                new RouteRule("50", "C")));

        WeightRouter router = new WeightRouter(policy);

        int totalRequests = 10000;
        Map<String, Integer> counts = new HashMap<>();

        for (int i = 0; i < totalRequests; i++) {
            RouteResult<String> result = router.route("user_" + i);
            String value = result.getValue();
            counts.put(value, counts.getOrDefault(value, 0) + 1);
        }

        // 验证大致的权重分布（允许一定范围内的统计误差）
        assertRange(counts.get("A"), 2000, 500);
        assertRange(counts.get("B"), 3000, 500);
        assertRange(counts.get("C"), 5000, 500);
    }

    private void assertRange(Integer actual, int expected, int delta) {
        Assert.assertNotNull("结果不应为空", actual);
        Assert.assertTrue("实际值: " + actual + ", 期望范围: [" + (expected - delta) + ", " + (expected + delta) + "]",
                actual >= expected - delta && actual <= expected + delta);
    }

    /**
     * 测试兜底逻辑
     */
    @Test
    public void testFallback() {
        RoutePolicy policy = new RoutePolicy();
        policy.setType("weight");
        policy.setFallbackValue("DEFAULT");

        WeightRouter router = new WeightRouter(policy);

        // 无规则时走兜底
        Assert.assertEquals("DEFAULT", router.route("any").getValue());

        // 请求对象为空时走兜底
        Assert.assertEquals("DEFAULT", router.route(null).getValue());
    }

    /**
     * 测试无效的权重格式
     */
    @Test(expected = RouteConfigException.class)
    public void testInvalidWeightFormat() {
        RoutePolicy policy = new RoutePolicy();
        policy.setRules(Collections.singletonList(new RouteRule("invalid", "A")));
        new WeightRouter(policy);
    }

    /**
     * 测试零权重规则是否被排在分流之外
     */
    @Test
    public void testZeroWeight() {
        RoutePolicy policy = new RoutePolicy();
        policy.setRules(Arrays.asList(
                new RouteRule("100", "A"),
                new RouteRule("0", "B")));
        WeightRouter router = new WeightRouter(policy);

        for (int i = 0; i < 100; i++) {
            Assert.assertEquals("A", router.route("user_" + i).getValue());
        }
    }
}
