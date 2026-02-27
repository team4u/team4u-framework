package com.team4u.framework.router;

import com.team4u.framework.router.api.model.RoutePolicy;
import com.team4u.framework.router.api.model.RouteRule;
import com.team4u.framework.router.api.trace.RouteTrace;
import com.team4u.framework.router.api.trace.RuleTrace;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 路由诊断功能单元测试
 */
public class RouteTraceTest {

    @Test
    public void testMapRouterTrace() {
        RoutePolicy policy = new RoutePolicy();
        policy.setType("map");
        policy.setFallbackValue("FallbackValue");
        policy.setRules(Arrays.asList(
                new RouteRule("A", "ValueA"),
                new RouteRule("B", "ValueB")));

        RoutingManager routingManager = RoutingManager.builder().build();
        String config = "{\"type\":\"map\",\"rules\":[{\"condition\":\"A\",\"value\":\"ValueA\"},{\"condition\":\"B\",\"value\":\"ValueB\"}],\"fallbackValue\":\"FallbackValue\"}";

        // 测试匹配成功
        RouteTrace<String> traceMatched = routingManager.traceByConfig(config, "A");
        Assert.assertTrue(traceMatched.getResult().isMatch());
        Assert.assertEquals("ValueA", traceMatched.getResult().getValue());
        Assert.assertEquals("map", traceMatched.getRouterType());
        Assert.assertEquals(1, traceMatched.getSteps().size());
        Assert.assertEquals("A", traceMatched.getSteps().get(0).getCondition());
        Assert.assertTrue(traceMatched.getSteps().get(0).isMatched());

        // 测试走入兜底
        RouteTrace<String> traceFallback = routingManager.traceByConfig(config, "C");
        Assert.assertTrue(traceFallback.getResult().isMatch());
        Assert.assertEquals("FallbackValue", traceFallback.getResult().getValue());
        Assert.assertEquals(2, traceFallback.getSteps().size());

        RuleTrace step1 = traceFallback.getSteps().get(0);
        Assert.assertEquals("C", step1.getCondition());
        Assert.assertFalse(step1.isMatched());
        Assert.assertFalse(step1.isFallback());

        RuleTrace step2 = traceFallback.getSteps().get(1);
        Assert.assertEquals("FALLBACK", step2.getCondition());
        Assert.assertTrue(step2.isMatched());
        Assert.assertTrue(step2.isFallback());
    }

    @Test
    public void testExpressionRouterTrace() {
        RoutePolicy policy = new RoutePolicy();
        policy.setType("expression");
        policy.setFallbackValue("FallbackValue");
        policy.setRules(Arrays.asList(
                new RouteRule("age > 18", "Adult"),
                new RouteRule("name == 'Admin'", "Admin")));

        RoutingManager routingManager = RoutingManager.builder().build();
        String config = "{\"type\":\"expression\",\"rules\":[{\"condition\":\"age > 18\",\"value\":\"Adult\"},{\"condition\":\"name == 'Admin'\",\"value\":\"Admin\"}],\"fallbackValue\":\"FallbackValue\"}";

        Map<String, Object> req = new HashMap<>();
        req.put("age", 20);

        // 测试匹配第一个规则
        RouteTrace<String> traceMatched = routingManager.traceByConfig(config, req);
        Assert.assertTrue(traceMatched.getResult().isMatch());
        Assert.assertEquals("Adult", traceMatched.getResult().getValue());
        Assert.assertEquals("expression", traceMatched.getRouterType());
        Assert.assertEquals(1, traceMatched.getSteps().size());

        RuleTrace step = traceMatched.getSteps().get(0);
        Assert.assertEquals("age > 18", step.getCondition());
        Assert.assertTrue(step.isMatched());
        // 验证 diagnosticDetail 是否包含 Criterion 的渲染结果
        Assert.assertNotNull(step.getDiagnosticDetail());
        Assert.assertTrue(step.getDiagnosticDetail().toString().contains("age > 18"));

        // 测试所有规则不匹配走入兜底
        req.put("age", 10);
        req.put("name", "User");
        RouteTrace<String> traceFallback = routingManager.traceByConfig(config, req);
        Assert.assertTrue(traceFallback.getResult().isMatch());
        Assert.assertEquals("FallbackValue", traceFallback.getResult().getValue());
        Assert.assertEquals(3, traceFallback.getSteps().size()); // 2 规则 + 1 兜底

        Assert.assertFalse(traceFallback.getSteps().get(0).isMatched());
        Assert.assertFalse(traceFallback.getSteps().get(1).isMatched());
        Assert.assertTrue(traceFallback.getSteps().get(2).isMatched());
        Assert.assertTrue(traceFallback.getSteps().get(2).isFallback());
    }

    @Test
    public void testRouterNotFoundTrace() {
        RoutingManager routingManager = RoutingManager.builder().build();
        RouteTrace<Object> trace = routingManager.trace("not_exist", new Object());
        Assert.assertFalse(trace.getResult().isMatch());
        Assert.assertNull(trace.getResult().getValue());
        Assert.assertTrue(trace.getSteps().isEmpty());
    }
}
