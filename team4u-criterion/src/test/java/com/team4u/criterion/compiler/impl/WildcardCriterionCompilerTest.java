package com.team4u.criterion.compiler.impl;

import org.junit.Assert;
import org.junit.Test;
import com.team4u.criterion.MatchContext;
import com.team4u.criterion.model.WildcardCriterion;

/**
 * WildcardEvaluator 测试类
 */
public class WildcardCriterionCompilerTest {

    private final WildcardCriterionCompiler wildcardEvaluator = new WildcardCriterionCompiler();

    @Test
    public void questionMark_matchesSingleChar() {
        // ? 匹配单个字符
        Assert.assertTrue(wildcardEvaluator.compile(new WildcardCriterion("a?c"), null).test(MatchContext.of("abc")));
        Assert.assertTrue(wildcardEvaluator.compile(new WildcardCriterion("a?c"), null).test(MatchContext.of("aXc")));
        Assert.assertFalse(wildcardEvaluator.compile(new WildcardCriterion("a?c"), null).test(MatchContext.of("ac"))); // 缺少一个字符
        Assert.assertFalse(wildcardEvaluator.compile(new WildcardCriterion("a?c"), null).test(MatchContext.of("abbc"))); // 多了一个字符
    }

    @Test
    public void asterisk_matchesMultipleChars() {
        // * 匹配多个字符
        Assert.assertTrue(wildcardEvaluator.compile(new WildcardCriterion("a*c"), null).test(MatchContext.of("ac")));
        Assert.assertTrue(wildcardEvaluator.compile(new WildcardCriterion("a*c"), null).test(MatchContext.of("abc")));
        Assert.assertTrue(wildcardEvaluator.compile(new WildcardCriterion("a*c"), null).test(MatchContext.of("abcdefghc")));
        Assert.assertFalse(wildcardEvaluator.compile(new WildcardCriterion("a*c"), null).test(MatchContext.of("abd")));
    }

    @Test
    public void multipleWildcards_matchCorrectly() {
        // 组合多个通配符
        Assert.assertTrue(wildcardEvaluator.compile(new WildcardCriterion("a*b*c"), null).test(MatchContext.of("abc")));
        Assert.assertTrue(wildcardEvaluator.compile(new WildcardCriterion("a*b*c"), null).test(MatchContext.of("aXXbYYc")));
        Assert.assertTrue(wildcardEvaluator.compile(new WildcardCriterion("a?b*c"), null).test(MatchContext.of("aXbc")));
        Assert.assertTrue(wildcardEvaluator.compile(new WildcardCriterion("a?b*c"), null).test(MatchContext.of("aXbYYYc")));
    }

    @Test
    public void emptyPattern_matchesEmptyString() {
        // 空 pattern 只匹配空字符串
        Assert.assertTrue(wildcardEvaluator.compile(new WildcardCriterion(""), null).test(MatchContext.of("")));
        Assert.assertFalse(wildcardEvaluator.compile(new WildcardCriterion(""), null).test(MatchContext.of("any")));
    }

    @Test
    public void asteriskOnly_matchesNonEmptyString() {
        // 单独的 * 匹配非空字符串，但 AntPathEvaluator 不匹配空字符串
        Assert.assertFalse(wildcardEvaluator.compile(new WildcardCriterion("*"), null).test(MatchContext.of("")));
        Assert.assertTrue(wildcardEvaluator.compile(new WildcardCriterion("*"), null).test(MatchContext.of("any")));
        Assert.assertTrue(wildcardEvaluator.compile(new WildcardCriterion("*"), null).test(MatchContext.of("anything at all")));
    }

    @Test
    public void nullPatternNullActual_returnsTrue() {
        // pattern 和 actual 都为 null 时，返回 true
        Assert.assertTrue(wildcardEvaluator.compile(new WildcardCriterion(null), null).test(MatchContext.of(null)));
    }

    @Test
    public void nullPatternNonNullActual_returnsFalse() {
        // pattern 为 null，actual 不为 null 时，返回 false
        Assert.assertFalse(wildcardEvaluator.compile(new WildcardCriterion(null), null).test(MatchContext.of("test")));
    }

    @Test
    public void nonNullPatternNullActual_returnsFalse() {
        // pattern 不为 null，actual 为 null 时，返回 false
        Assert.assertFalse(wildcardEvaluator.compile(new WildcardCriterion("test"), null).test(MatchContext.of(null)));
    }

    @Test
    public void prefixMatch_matchesCorrectly() {
        // 前缀匹配
        Assert.assertTrue(wildcardEvaluator.compile(new WildcardCriterion("hello*"), null).test(MatchContext.of("hello")));
        Assert.assertTrue(wildcardEvaluator.compile(new WildcardCriterion("hello*"), null).test(MatchContext.of("hello world")));
        Assert.assertFalse(wildcardEvaluator.compile(new WildcardCriterion("hello*"), null).test(MatchContext.of("say hello")));
    }

    @Test
    public void suffixMatch_matchesCorrectly() {
        // 后缀匹配
        Assert.assertTrue(wildcardEvaluator.compile(new WildcardCriterion("*world"), null).test(MatchContext.of("world")));
        Assert.assertTrue(wildcardEvaluator.compile(new WildcardCriterion("*world"), null).test(MatchContext.of("hello world")));
        Assert.assertFalse(wildcardEvaluator.compile(new WildcardCriterion("*world"), null).test(MatchContext.of("world tour")));
    }

    @Test
    public void containsMatch_matchesCorrectly() {
        // 包含匹配
        Assert.assertTrue(wildcardEvaluator.compile(new WildcardCriterion("*hello*"), null).test(MatchContext.of("hello")));
        Assert.assertTrue(wildcardEvaluator.compile(new WildcardCriterion("*hello*"), null).test(MatchContext.of("say hello world")));
        Assert.assertFalse(wildcardEvaluator.compile(new WildcardCriterion("*hello*"), null).test(MatchContext.of("hi there")));
    }

    @Test
    public void nonStringActual_convertsToString() {
        // actual 为非字符串类型时，会通过 toString 转换
        Assert.assertTrue(wildcardEvaluator.compile(new WildcardCriterion("123"), null).test(MatchContext.of(123)));
        Assert.assertTrue(wildcardEvaluator.compile(new WildcardCriterion("12*"), null).test(MatchContext.of(12345)));
        Assert.assertTrue(wildcardEvaluator.compile(new WildcardCriterion("*5"), null).test(MatchContext.of(12345)));
    }

    @Test
    public void specialCharsInPattern_matchesLiterally() {
        // AntPathEvaluator 中的特殊字符处理
        Assert.assertTrue(wildcardEvaluator.compile(new WildcardCriterion("test.txt"), null).test(MatchContext.of("test.txt")));
        Assert.assertTrue(wildcardEvaluator.compile(new WildcardCriterion("*.txt"), null).test(MatchContext.of("file.txt")));
    }
}
