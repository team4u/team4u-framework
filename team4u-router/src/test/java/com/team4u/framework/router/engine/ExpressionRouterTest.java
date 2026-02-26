package com.team4u.framework.router.engine;

import com.team4u.framework.router.api.RoutePolicy;
import com.team4u.framework.router.api.RouteResult;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ExpressionRouter 单元测试
 */
public class ExpressionRouterTest {

    @Test
    public void testRoute() {
        RoutePolicy policy = new RoutePolicy();
        policy.setType("expression");
        LinkedHashMap<String, Object> rules = new LinkedHashMap<>();
        // 规则 1: name 等于 'A'
        rules.put("name == 'A'", "ValueA");
        // 规则 2: age 大于 18
        rules.put("age > 18", "ValueAdult");
        // 规则 3: 兜底
        rules.put("*", "ValueDefault");
        policy.setRules(rules);

        ExpressionRouter router = new ExpressionRouter(policy);

        // 匹配规则 1
        Map<String, Object> req1 = new HashMap<>();
        req1.put("name", "A");
        RouteResult<String> result1 = router.route(req1);
        Assert.assertTrue(result1.isMatch());
        Assert.assertEquals("ValueA", result1.getValue());

        // 匹配规则 2 (即便满足后续规则，也会在满足规则 2 时短路)
        Map<String, Object> req2 = new HashMap<>();
        req2.put("name", "B");
        req2.put("age", 20);
        RouteResult<String> result2 = router.route(req2);
        Assert.assertTrue(result2.isMatch());
        Assert.assertEquals("ValueAdult", result2.getValue());

        // 匹配兜底
        Map<String, Object> req3 = new HashMap<>();
        req3.put("name", "B");
        req3.put("age", 10);
        RouteResult<String> result3 = router.route(req3);
        Assert.assertTrue(result3.isMatch());
        Assert.assertEquals("ValueDefault", result3.getValue());
    }

    @Test
    public void testOrderImportance() {
        RoutePolicy policy = new RoutePolicy();
        LinkedHashMap<String, Object> rules = new LinkedHashMap<>();
        // 故意让范围大的在前
        rules.put("age > 10", "EarlyMatch");
        rules.put("age > 20", "LateMatch");
        policy.setRules(rules);

        ExpressionRouter router = new ExpressionRouter(policy);

        Map<String, Object> req = new HashMap<>();
        req.put("age", 30);
        RouteResult<String> result = router.route(req);
        // 应该匹配第一个，体现顺序重要性
        Assert.assertEquals("EarlyMatch", result.getValue());
    }
}