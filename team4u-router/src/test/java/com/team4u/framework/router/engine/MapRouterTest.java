package com.team4u.framework.router.engine;

import com.team4u.framework.router.api.RoutePolicy;
import com.team4u.framework.router.api.RouteResult;
import com.team4u.framework.router.api.RouteRule;
import com.team4u.framework.router.api.trace.RouteTrace;
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

        // 验证 trace() 方法返回的 matchedCondition
        RouteTrace<String> traceA = router.trace("A");
        Assert.assertTrue(traceA.getResult().isMatch());
        Assert.assertEquals("A", traceA.getResult().getMatchedCondition());
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
    public void testRouteWithTypeConversion() {
        RoutePolicy policy = new RoutePolicy();
        policy.setType("map");
        policy.setRules(Arrays.asList(
                new RouteRule("1", "100"),
                new RouteRule("2", "200")));

        MapRouter router = new MapRouter(policy);

        // 测试从 String 到 Integer 的自动转换
        RouteResult<Integer> result = router.route("1", Integer.class);
        Assert.assertTrue(result.isMatch());
        Assert.assertEquals(Integer.valueOf(100), result.getValue());

        // 再次调用，确认无缓存情况下依然正确（虽然目前不再测试缓存，但确保逻辑通畅）
        RouteResult<Integer> result2 = router.route("2", Integer.class);
        Assert.assertTrue(result2.isMatch());
        Assert.assertEquals(Integer.valueOf(200), result2.getValue());
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
