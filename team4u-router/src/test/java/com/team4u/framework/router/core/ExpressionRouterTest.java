package com.team4u.framework.router.core;

import com.team4u.framework.criterion.Criteria;
import com.team4u.framework.router.api.builder.RoutePolicyBuilder;
import com.team4u.framework.router.api.model.RoutePolicy;
import com.team4u.framework.router.api.model.RouteResult;
import com.team4u.framework.router.api.model.RouteRule;
import com.team4u.framework.router.api.trace.RouteTrace;
import org.junit.Assert;
import org.junit.Test;

import java.util.*;

/**
 * ExpressionRouter 单元测试
 */
public class ExpressionRouterTest {

    @Test
    public void testRoute() {
        RoutePolicy policy = new RoutePolicy();
        policy.setType("expression");
        // 设置标准兜底字段
        policy.setFallbackValue("ValueDefault");

        policy.setRules(Arrays.asList(
                new RouteRule("name == 'A'", "ValueA"),
                new RouteRule("age > 18", "ValueAdult")));

        ExpressionRouter router = new ExpressionRouter(policy);

        // 匹配规则 1
        Map<String, Object> req1 = new HashMap<>();
        req1.put("name", "A");
        RouteResult<String> result1 = router.route(req1);
        Assert.assertTrue(result1.isMatch());
        Assert.assertEquals("ValueA", result1.getValue());
        Assert.assertEquals("name == 'A'", result1.getMatchedCondition());

        // 匹配规则 2 (即便满足后续规则，也会在满足规则 2 时短路)
        Map<String, Object> req2 = new HashMap<>();
        req2.put("name", "B");
        req2.put("age", 20);
        RouteResult<String> result2 = router.route(req2);
        Assert.assertTrue(result2.isMatch());
        Assert.assertEquals("ValueAdult", result2.getValue());
        Assert.assertEquals("age > 18", result2.getMatchedCondition());

        // 匹配显式兜底
        Map<String, Object> req3 = new HashMap<>();
        req3.put("name", "B");
        req3.put("age", 10);
        RouteResult<String> result3 = router.route(req3);
        Assert.assertTrue(result3.isMatch());
        Assert.assertEquals("ValueDefault", result3.getValue());
        Assert.assertNull(result3.getMatchedCondition());

        // 验证 trace() 方法返回的 matchedCondition
        RouteTrace<String> trace1 = router.trace(req1);
        Assert.assertTrue(trace1.getResult().isMatch());
        Assert.assertEquals("name == 'A'", trace1.getResult().getMatchedCondition());

        RouteTrace<String> trace2 = router.trace(req2);
        Assert.assertTrue(trace2.getResult().isMatch());
        Assert.assertEquals("age > 18", trace2.getResult().getMatchedCondition());
    }

    @Test
    public void testOrderImportance() {
        RoutePolicy policy = new RoutePolicy();
        policy.setRules(Arrays.asList(
                new RouteRule("age > 10", "EarlyMatch"),
                new RouteRule("age > 20", "LateMatch")));

        ExpressionRouter router = new ExpressionRouter(policy);

        Map<String, Object> req = new HashMap<>();
        req.put("age", 30);
        RouteResult<String> result = router.route(req);
        // 应该匹配第一个，体现顺序重要性
        Assert.assertEquals("EarlyMatch", result.getValue());
    }

    @Test
    public void testCustomCriteria() {
        RoutePolicy policy = new RoutePolicy();
        policy.setRules(Collections.singletonList(
                new RouteRule("name is_special true", "Matched")));

        // 创建自定义 Criteria，支持 is_special 操作符
        Criteria criteria = Criteria.builder()
                .addOperator("is_special", (actual, expected) -> "special".equals(actual))
                .build();

        ExpressionRouter router = new ExpressionRouter(policy, criteria);

        Map<String, Object> req = new HashMap<>();
        req.put("name", "special");
        RouteResult<String> result = router.route(req);
        Assert.assertTrue(result.isMatch());
        Assert.assertEquals("Matched", result.getValue());

        req.put("name", "normal");
        result = router.route(req);
        Assert.assertFalse(result.isMatch());
    }

    @Test
    public void testFallbackValue() {
        RoutePolicy policy = new RoutePolicy();
        policy.setFallbackValue("ExplicitFallback");

        policy.setRules(Collections.singletonList(
                new RouteRule("name == 'A'", "ValueA")));

        ExpressionRouter router = new ExpressionRouter(policy);

        // 精准匹配正常工作
        Map<String, Object> req1 = new HashMap<>();
        req1.put("name", "A");
        RouteResult<String> result1 = router.route(req1);
        Assert.assertTrue(result1.isMatch());
        Assert.assertEquals("ValueA", result1.getValue());

        // 所有规则未命中时走 fallbackValue
        Map<String, Object> req2 = new HashMap<>();
        req2.put("name", "B");
        RouteResult<String> result2 = router.route(req2);
        Assert.assertTrue(result2.isMatch());
        Assert.assertEquals("ExplicitFallback", result2.getValue());
    }

    @Test
    public void testMultiMatch() {
        RoutePolicy policy = new RoutePolicy();
        policy.setType("expression");
        policy.getExt().put("multiMatch", true);
        policy.setFallbackValue(Collections.singletonList("default"));

        policy.setRules(Arrays.asList(
                new RouteRule("age > 10", "V10"),
                new RouteRule("age > 20", "V20"),
                new RouteRule("age > 30", "V30")));

        ExpressionRouter router = new ExpressionRouter(policy);

        // 匹配多个规则
        Map<String, Object> req1 = new HashMap<>();
        req1.put("age", 25);
        RouteResult<List<String>> result1 = router.route(req1);
        Assert.assertTrue(result1.isMatch());
        Assert.assertEquals(Arrays.asList("V10", "V20"), result1.getValue());
        Assert.assertEquals("age > 10", result1.getMatchedCondition());
        Assert.assertEquals(Arrays.asList("age > 10", "age > 20"), result1.getMatchedConditions());

        // 匹配全部规则
        Map<String, Object> req2 = new HashMap<>();
        req2.put("age", 35);
        RouteResult<List<String>> result2 = router.route(req2);
        Assert.assertEquals(Arrays.asList("V10", "V20", "V30"), result2.getValue());
        Assert.assertEquals(Arrays.asList("age > 10", "age > 20", "age > 30"), result2.getMatchedConditions());

        // 走兜底逻辑
        Map<String, Object> req3 = new HashMap<>();
        req3.put("age", 5);
        RouteResult<List<String>> result3 = router.route(req3);
        Assert.assertEquals(Collections.singletonList("default"), result3.getValue());

        // 验证 trace
        RouteTrace<List<String>> trace1 = router.trace(req1);
        Assert.assertEquals(Arrays.asList("V10", "V20"), trace1.getResult().getValue());
        Assert.assertEquals("age > 10", trace1.getResult().getMatchedCondition());
        Assert.assertEquals(Arrays.asList("age > 10", "age > 20"), trace1.getResult().getMatchedConditions());
    }

    @Test
    public void testRoutePolicyBuilderWithExt() {
        RoutePolicy policy = RoutePolicyBuilder.expression()
                .ext("multiMatch", true)
                .ext("other", "value")
                .build();

        Assert.assertTrue(policy.getExtProperty("multiMatch", false));
        Assert.assertEquals("value", policy.getExtProperty("other", "default"));
    }

    @Test
    public void testGetExtPropertyWithNullDefaultValue() {
        RoutePolicy policy = new RoutePolicy();
        policy.getExt().put("threshold", "10");
        policy.getExt().put("name", "router-a");

        Assert.assertEquals(Integer.valueOf(10), policy.getExtProperty("threshold", Integer.class, null));
        Assert.assertEquals("router-a", policy.getExtProperty("name", String.class, null));
        Assert.assertNull(policy.getExtProperty("missing", String.class, null));
        Assert.assertEquals("10", policy.getExtProperty("threshold", (String) null));
    }
}
