package com.team4u.framework.router.parser;

import com.team4u.framework.router.api.model.RoutePolicy;
import org.junit.Assert;
import org.junit.Test;

public class DefaultRoutePolicyParserTest {

    private final DefaultRoutePolicyParser parser = new DefaultRoutePolicyParser();

    @Test
    public void testParseWithMissingRules() {
        // 当 JSON 中缺少 rules 字段时，测试是否会被设置为 null
        String json = "{\"id\":\"1\", \"type\":\"map\"}";
        RoutePolicy policy = parser.parse(json);

        Assert.assertNotNull("Policy should not be null", policy);
        Assert.assertNotNull("Rules should not be null even if missing in JSON", policy.getRules());
        Assert.assertTrue("Rules should be empty", policy.getRules().isEmpty());
    }

    @Test
    public void testParseWithNullRules() {
        // 当 JSON 中显式设置 rules 为 null 时
        String json = "{\"id\":\"1\", \"type\":\"map\", \"rules\": null}";
        RoutePolicy policy = parser.parse(json);

        Assert.assertNotNull("Policy should not be null", policy);
        Assert.assertNotNull("Rules should not be null even if explicitly null in JSON", policy.getRules());
    }
}
