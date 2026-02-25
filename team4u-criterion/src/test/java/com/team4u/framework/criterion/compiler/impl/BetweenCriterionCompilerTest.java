package com.team4u.framework.criterion.compiler.impl;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.date.DateUtil;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import com.team4u.framework.criterion.MatchContext;
import com.team4u.framework.criterion.model.BetweenCriterion;
import com.team4u.framework.criterion.model.SmartCompareCriterion;
import com.team4u.framework.criterion.model.value.FixedValue;
import com.team4u.framework.criterion.model.value.ValueFactory;

import java.math.BigDecimal;

public class BetweenCriterionCompilerTest {

    private BetweenCriterionCompiler compiler;

    @Before
    public void setUp() {
        compiler = new BetweenCriterionCompiler();
    }

    @Test
    public void testKey() {
        Assert.assertEquals(BetweenCriterion.class, compiler.key());
        Assert.assertNotEquals(SmartCompareCriterion.class, compiler.key());
    }

    @Test
    public void testClosedIntervalIncludeBoth() {
        BetweenCriterion criterion = new BetweenCriterion(
                ValueFactory.create("18", Convert::toBigDecimal, BigDecimal.class),
                ValueFactory.create("30", Convert::toBigDecimal, BigDecimal.class),
                true,
                true,
                Convert::toBigDecimal);

        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(25)));
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(18)));
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(30)));
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of(17)));
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of(31)));
    }

    @Test
    public void testOpenIntervalExcludeBoth() {
        BetweenCriterion criterion = new BetweenCriterion(
                ValueFactory.create("18", Convert::toBigDecimal, BigDecimal.class),
                ValueFactory.create("30", Convert::toBigDecimal, BigDecimal.class),
                false,
                false,
                Convert::toBigDecimal);

        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(25)));
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of(18)));
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of(30)));
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of(17)));
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of(31)));
    }

    @Test
    public void testMixedIntervalLowerInclude() {
        // [18, 30)
        BetweenCriterion criterion = new BetweenCriterion(
                ValueFactory.create("18", Convert::toBigDecimal, BigDecimal.class),
                ValueFactory.create("30", Convert::toBigDecimal, BigDecimal.class),
                true,
                false,
                Convert::toBigDecimal);

        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(18)));
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(29)));
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of(30)));
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of(17)));
    }

    @Test
    public void testMixedIntervalUpperInclude() {
        // (18, 30]
        BetweenCriterion criterion = new BetweenCriterion(
                ValueFactory.create("18", Convert::toBigDecimal, BigDecimal.class),
                ValueFactory.create("30", Convert::toBigDecimal, BigDecimal.class),
                false,
                true,
                Convert::toBigDecimal);

        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(19)));
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(30)));
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of(18)));
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of(31)));
    }

    @Test
    public void testDecimalValues() {
        BetweenCriterion criterion = new BetweenCriterion(
                ValueFactory.create("90.5", Convert::toBigDecimal, BigDecimal.class),
                ValueFactory.create("100.0", Convert::toBigDecimal, BigDecimal.class),
                true,
                true,
                Convert::toBigDecimal);

        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(95.5)));
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(90.5)));
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(100.0)));
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of(90.4)));
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of(100.1)));
    }

    @Test
    public void testNullActual() {
        BetweenCriterion criterion = new BetweenCriterion(
                ValueFactory.create("18", Convert::toBigDecimal, BigDecimal.class),
                ValueFactory.create("30", Convert::toBigDecimal, BigDecimal.class),
                true,
                true,
                Convert::toBigDecimal);

        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of(null)));
    }

    @Test
    public void testStringActual() {
        BetweenCriterion criterion = new BetweenCriterion(
                ValueFactory.create("18", Convert::toBigDecimal, BigDecimal.class),
                ValueFactory.create("30", Convert::toBigDecimal, BigDecimal.class),
                true,
                true,
                Convert::toBigDecimal);

        // 字符串无法转换为数字时不匹配
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of("abc")));
    }

    @Test
    public void testStringNumberActual() {
        BetweenCriterion criterion = new BetweenCriterion(
                ValueFactory.create("18", Convert::toBigDecimal, BigDecimal.class),
                ValueFactory.create("30", Convert::toBigDecimal, BigDecimal.class),
                true,
                true,
                Convert::toBigDecimal);

        // 字符串数字可以转换后匹配
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of("25")));
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of("18")));
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of("30")));
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of("17")));
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of("31")));
    }

    @Test
    public void testNegativeRange() {
        BetweenCriterion criterion = new BetweenCriterion(
                ValueFactory.create("-10", Convert::toBigDecimal, BigDecimal.class),
                ValueFactory.create("5", Convert::toBigDecimal, BigDecimal.class),
                true,
                true,
                Convert::toBigDecimal);

        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(-5)));
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(-10)));
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(5)));
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of(-11)));
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of(6)));
    }

    @Test
    public void testZeroCrossing() {
        BetweenCriterion criterion = new BetweenCriterion(
                ValueFactory.create("-5", Convert::toBigDecimal, BigDecimal.class),
                ValueFactory.create("5", Convert::toBigDecimal, BigDecimal.class),
                true,
                true,
                Convert::toBigDecimal);

        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(0)));
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(-5)));
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(5)));
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of(-6)));
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of(6)));
    }

    @Test
    public void testIntegerActual() {
        BetweenCriterion criterion = new BetweenCriterion(
                ValueFactory.create("1", Convert::toBigDecimal, BigDecimal.class),
                ValueFactory.create("100", Convert::toBigDecimal, BigDecimal.class),
                true,
                true,
                Convert::toBigDecimal);

        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(50)));
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(1)));
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(100)));
    }

    @Test
    public void testLongActual() {
        BetweenCriterion criterion = new BetweenCriterion(
                ValueFactory.create("1000", Convert::toBigDecimal, BigDecimal.class),
                ValueFactory.create("9999", Convert::toBigDecimal, BigDecimal.class),
                true,
                true,
                Convert::toBigDecimal);

        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(5000L)));
    }

    @Test
    public void testBigDecimalActual() {
        BetweenCriterion criterion = new BetweenCriterion(
                ValueFactory.create("10.5", Convert::toBigDecimal, BigDecimal.class),
                ValueFactory.create("20.5", Convert::toBigDecimal, BigDecimal.class),
                true,
                true,
                Convert::toBigDecimal);

        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(new BigDecimal("15.5"))));
    }

    @Test
    public void testEqualBoundaries() {
        // 边界值相等的闭区间应该只匹配该值
        BetweenCriterion criterion = new BetweenCriterion(
                ValueFactory.create("50", Convert::toBigDecimal, BigDecimal.class),
                ValueFactory.create("50", Convert::toBigDecimal, BigDecimal.class),
                true,
                true,
                Convert::toBigDecimal);

        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(50)));
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of(49)));
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of(51)));
    }

    @Test
    public void testEqualBoundariesOpen() {
        // 边界值相等的开区间不匹配任何值
        BetweenCriterion criterion = new BetweenCriterion(
                ValueFactory.create("50", Convert::toBigDecimal, BigDecimal.class),
                ValueFactory.create("50", Convert::toBigDecimal, BigDecimal.class),
                false,
                false,
                Convert::toBigDecimal);

        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of(50)));
    }

    @Test
    public void testDateRange() {
        // 验证日期字符串区间匹配 (补齐原 TimeBetweenEvaluatorTest 的逻辑)
        BetweenCriterion criterion = new BetweenCriterion(
                new FixedValue<>("2023-01-01"),
                new FixedValue<>("2023-01-10"),
                true,
                true,
                obj -> DateUtil.parse(obj.toString()));

        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of("2023-01-01")));
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of("2023-01-05")));
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of("2022-12-31")));
    }
}