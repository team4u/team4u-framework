package com.team4u.framework.router.api.model;

import org.junit.Assert;
import org.junit.Test;

/**
 * RouteResult 单元测试
 */
public class RouteResultTest {

    @Test
    public void testUnmatchIsSingleton() {
        // 验证多次调用 unmatch() 返回的是同一个实例（单例）
        RouteResult<Object> result1 = RouteResult.unmatch();
        RouteResult<String> result2 = RouteResult.unmatch();

        Assert.assertSame("unmatch() 必须返回相同的实例", result1, result2);
        Assert.assertFalse(result1.isMatch());
        Assert.assertNull(result1.getValue());
        Assert.assertNull(result1.getMatchedCondition());
    }

    @Test
    public void testMatchedIsNOTSingleton() {
        // 验证 fallbackMatch() 每次调用返回的是新实例
        RouteResult<String> result1 = RouteResult.fallbackMatch("A");
        RouteResult<String> result2 = RouteResult.fallbackMatch("A");

        Assert.assertNotSame("matched() 每次调用应返回新实例", result1, result2);
        Assert.assertTrue(result1.isMatch());
        Assert.assertEquals("A", result1.getValue());
        Assert.assertTrue(result1.isFallbackMatch());
    }

    @Test
    public void testExplicitOutcomeFactories() {
        RouteResult<String> ruleMatch = RouteResult.ruleMatch("A", "cond");
        RouteResult<String> fallbackMatch = RouteResult.fallbackMatch("B");
        RouteResult<String> shortCircuited = RouteResult.shortCircuited("C", "hit");

        Assert.assertTrue(ruleMatch.isRuleMatch());
        Assert.assertEquals(RouteOutcome.RULE_MATCH, ruleMatch.getOutcome());
        Assert.assertTrue(fallbackMatch.isFallbackMatch());
        Assert.assertEquals(RouteOutcome.FALLBACK_MATCH, fallbackMatch.getOutcome());
        Assert.assertTrue(shortCircuited.isShortCircuited());
        Assert.assertEquals(RouteOutcome.SHORT_CIRCUITED, shortCircuited.getOutcome());
    }
}
