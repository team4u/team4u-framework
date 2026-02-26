package com.team4u.framework.router.engine;

import com.team4u.framework.router.api.RoutePolicy;
import com.team4u.framework.router.api.RouteResult;
import org.junit.Assert;
import org.junit.Test;

import java.util.LinkedHashMap;

/**
 * MapRouter 单元测试
 */
public class MapRouterTest {

    @Test
    public void testRoute() {
        RoutePolicy policy = new RoutePolicy();
        policy.setType("map");
        LinkedHashMap<String, Object> rules = new LinkedHashMap<>();
        rules.put("A", "ValueA");
        rules.put("B", "ValueB");
        rules.put("*", "ValueDefault");
        policy.setRules(rules);

        MapRouter router = new MapRouter(policy);

        // 测试精准匹配
        RouteResult<String> resultA = router.route("A");
        Assert.assertTrue(resultA.isMatch());
        Assert.assertEquals("ValueA", resultA.getValue());

        // 测试不存在的 Key 走兜底
        RouteResult<String> resultC = router.route("C");
        Assert.assertTrue(resultC.isMatch());
        Assert.assertEquals("ValueDefault", resultC.getValue());

        // 测试 null 走兜底
        RouteResult<String> resultNull = router.route(null);
        Assert.assertTrue(resultNull.isMatch());
        Assert.assertEquals("ValueDefault", resultNull.getValue());
    }

    @Test
    public void testNoFallback() {
        RoutePolicy policy = new RoutePolicy();
        LinkedHashMap<String, Object> rules = new LinkedHashMap<>();
        rules.put("A", "ValueA");
        policy.setRules(rules);

        MapRouter router = new MapRouter(policy);

        RouteResult<String> resultC = router.route("C");
        Assert.assertFalse(resultC.isMatch());
    }
}
