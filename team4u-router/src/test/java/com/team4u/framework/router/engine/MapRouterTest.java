package com.team4u.framework.router.engine;

import com.team4u.framework.router.api.RoutePolicy;
import com.team4u.framework.router.api.RouteResult;
import com.team4u.framework.router.api.RouteRule;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

/**
 * MapRouter 单元测试
 */
public class MapRouterTest {

    @Test
    public void testRoute() {
        RoutePolicy policy = new RoutePolicy();
        policy.setType("map");
        policy.setRules(Arrays.asList(
                new RouteRule("A", "ValueA"),
                new RouteRule("B", "ValueB")));
        // 使用标准的兜底字段
        policy.setFallbackValue("ValueDefault");

        MapRouter router = new MapRouter(policy);

        // 测试精准匹配
        RouteResult<String> resultA = router.route("A");
        Assert.assertTrue(resultA.isMatch());
        Assert.assertEquals("ValueA", resultA.getValue());
        Assert.assertEquals("A", resultA.getMatchedCondition());

        // 测试不存在的 Key 走兜底
        RouteResult<String> resultC = router.route("C");
        Assert.assertTrue(resultC.isMatch());
        Assert.assertEquals("ValueDefault", resultC.getValue());
        Assert.assertNull(resultC.getMatchedCondition());

        // 测试 null 走兜底
        RouteResult<String> resultNull = router.route(null);
        Assert.assertTrue(resultNull.isMatch());
        Assert.assertEquals("ValueDefault", resultNull.getValue());
        Assert.assertNull(resultNull.getMatchedCondition());
    }

    @Test
    public void testNoFallback() {
        RoutePolicy policy = new RoutePolicy();
        policy.setRules(Arrays.asList(
                new RouteRule("A", "ValueA")));

        MapRouter router = new MapRouter(policy);

        RouteResult<String> resultC = router.route("C");
        Assert.assertFalse(resultC.isMatch());
    }

    @Test
    public void testFallbackValue() {
        RoutePolicy policy = new RoutePolicy();
        policy.setFallbackValue("ExplicitFallback");

        policy.setRules(Arrays.asList(
                new RouteRule("A", "ValueA"),
                new RouteRule("*", "ValueStar")));

        MapRouter router = new MapRouter(policy);

        // 精准匹配正常工作
        Assert.assertEquals("ValueA", router.<String>route("A").getValue());

        // 此时命中 * 会返回其对应值，不再作为降级兜底方案
        Assert.assertEquals("ValueStar", router.<String>route("*").getValue());

        // 其他不存在的 Key 统一走显式的 fallbackValue
        RouteResult<String> result = router.route("B");
        Assert.assertTrue(result.isMatch());
        Assert.assertEquals("ExplicitFallback", result.getValue());
    }
}
