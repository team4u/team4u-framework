package com.team4u.framework.criterion.compiler.impl;

import cn.hutool.core.date.DateUtil;
import org.junit.Assert;
import org.junit.Test;
import com.team4u.framework.criterion.Criteria;
import com.team4u.framework.criterion.MatchContext;
import com.team4u.framework.criterion.model.SmartCompareCriterion;
import com.team4u.framework.criterion.model.value.VariableValue;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 智能比较规则单元测试
 */
public class SmartCompareCriterionCompilerTest {

    private final Criteria criteria = Criteria.global();

    @Test
    public void testFixedNumberCompare() {
        Assert.assertTrue(criteria.matches("it > 50", 100));
        Assert.assertTrue(criteria.matches("it == 100", 100L));
        Assert.assertTrue(criteria.matches("it > 100", 100.5));
        Assert.assertTrue(criteria.matches("it > 100", "100.5"));
        Assert.assertTrue(criteria.matches("it == 12345678901234567890", new BigDecimal("12345678901234567890")));
    }

    @Test
    public void testFixedStringCompare() {
        Assert.assertTrue(criteria.matches("it == 'v1.0.0'", "v1.0.0"));
        Assert.assertTrue(criteria.matches("it < 'v2.0.0'", "v1.5.0"));
    }

    @Test
    public void testDateCompare() {
        Date now = new Date();
        Date tomorrow = DateUtil.offsetDay(now, 1);

        MatchContext context = MatchContext.of(now).setAttribute("tomorrow", tomorrow);
        SmartCompareCriterion criterion = new SmartCompareCriterion("<", new VariableValue<>("tomorrow", Object.class));
        Assert.assertTrue(compileAndTest(criterion, context));

        long ts = System.currentTimeMillis();
        Assert.assertTrue(criteria.matches("it == " + ts, ts));
    }

    @Test
    public void testNullHandling() {
        MatchContext context = MatchContext.of(null).setAttribute("var", null);
        SmartCompareCriterion criterion = new SmartCompareCriterion("==", new VariableValue<>("var", Object.class));
        Assert.assertTrue(compileAndTest(criterion, context));

        SmartCompareCriterion criterion2 = new SmartCompareCriterion("==", new VariableValue<>("other", Object.class));
        MatchContext context2 = MatchContext.of(null).setAttribute("other", 10);
        Assert.assertFalse(compileAndTest(criterion2, context2));
    }

    @Test
    public void testStrictModeExceptions() {
        MatchContext context = MatchContext.of("not a number").withStrictMode(true);
        try {
            criteria.matches("it > 10", context);
            Assert.fail("应抛出异常");
        } catch (Exception e) {
            Assert.assertTrue(e.getMessage().contains("无效的数字格式"));
        }
    }

    @Test
    public void testTypeMismatchFallback() {
        MatchContext context = MatchContext.of(true).withStrictMode(false);
        context.setAttribute("obj", new Object());
        SmartCompareCriterion criterion = new SmartCompareCriterion("==", new VariableValue<>("obj", Object.class));
        Assert.assertFalse(compileAndTest(criterion, context));
    }

    private boolean compileAndTest(SmartCompareCriterion criterion, MatchContext context) {
        SmartCompareCriterionCompiler compiler = new SmartCompareCriterionCompiler();
        return compiler.compile(criterion, null).test(context);
    }
}