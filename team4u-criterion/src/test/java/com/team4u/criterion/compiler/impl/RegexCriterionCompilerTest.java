package com.team4u.criterion.compiler.impl;

import org.junit.Assert;
import org.junit.Test;
import com.team4u.criterion.MatchContext;
import com.team4u.criterion.model.RegexCriterion;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * RegexEvaluator 测试类
 */
public class RegexCriterionCompilerTest {

    private final RegexCriterionCompiler evaluator = new RegexCriterionCompiler();

    @Test
    public void anyPattern_matchesNonEmptyString() {
        // pattern 为 ".*" 时，应匹配任意非空字符串
        Pattern anyPattern = Pattern.compile(".*");
        Assert.assertTrue(evaluator.compile(new RegexCriterion(anyPattern), null).test(MatchContext.of("hello")));
        Assert.assertTrue(evaluator.compile(new RegexCriterion(anyPattern), null).test(MatchContext.of("any string")));
        Assert.assertTrue(evaluator.compile(new RegexCriterion(anyPattern), null).test(MatchContext.of("123")));
        Assert.assertTrue(evaluator.compile(new RegexCriterion(anyPattern), null).test(MatchContext.of("a")));
    }

    @Test
    public void anyPattern_matchesEmptyString() {
        // pattern 为 ".*"，可以匹配空字符串
        Pattern anyPattern = Pattern.compile(".*");
        Assert.assertTrue(evaluator.compile(new RegexCriterion(anyPattern), null).test(MatchContext.of("")));
    }

    @Test
    public void anyPattern_actualIsNull_matchesEmptyString() {
        // actual 为 null 时，Convert.toStr(null, "") 返回 ""
        // pattern ".*" 匹配空字符串，所以返回 true
        Pattern anyPattern = Pattern.compile(".*");
        Assert.assertTrue(evaluator.compile(new RegexCriterion(anyPattern), null).test(MatchContext.of(null)));
    }

    @Test
    public void normalPattern_matchesCorrectly() {
        // 测试正常的正则表达式匹配
        Pattern pattern = Pattern.compile("^A.*");
        Assert.assertTrue(evaluator.compile(new RegexCriterion(pattern), null).test(MatchContext.of("Apple")));
        Assert.assertTrue(evaluator.compile(new RegexCriterion(pattern), null).test(MatchContext.of("Ant")));
        Assert.assertFalse(evaluator.compile(new RegexCriterion(pattern), null).test(MatchContext.of("apple"))); // 小写 a 不匹配
        Assert.assertFalse(evaluator.compile(new RegexCriterion(pattern), null).test(MatchContext.of("Banana")));
    }

    @Test
    public void digitPattern_matchesCorrectly() {
        // 测试数字正则
        Pattern pattern = Pattern.compile("^\\d+$");
        Assert.assertTrue(evaluator.compile(new RegexCriterion(pattern), null).test(MatchContext.of("12345")));
        Assert.assertFalse(evaluator.compile(new RegexCriterion(pattern), null).test(MatchContext.of("123abc")));
        Assert.assertFalse(evaluator.compile(new RegexCriterion(pattern), null).test(MatchContext.of("")));
    }

    @Test
    public void emailPattern_matchesCorrectly() {
        // 测试邮箱正则
        Pattern emailPattern = Pattern.compile("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");
        Assert.assertTrue(evaluator.compile(new RegexCriterion(emailPattern), null).test(MatchContext.of("test@example.com")));
        Assert.assertTrue(
                evaluator.compile(new RegexCriterion(emailPattern), null).test(MatchContext.of("user.name@domain.org")));
        Assert.assertFalse(evaluator.compile(new RegexCriterion(emailPattern), null).test(MatchContext.of("invalid-email")));
        Assert.assertFalse(evaluator.compile(new RegexCriterion(emailPattern), null).test(MatchContext.of("@domain.com")));
    }

    @Test
    public void nullPatternAndNullActual_returnsTrue() {
        // pattern 和 actual 都为 null 时，返回 true
        Assert.assertTrue(evaluator.compile(new RegexCriterion(null), null).test(MatchContext.of(null)));
    }

    @Test
    public void nullPatternAndNonNullActual_returnsFalse() {
        // pattern 为 null，actual 不为 null 时，返回 false
        Assert.assertFalse(evaluator.compile(new RegexCriterion(null), null).test(MatchContext.of("test")));
    }

    @Test(expected = PatternSyntaxException.class)
    public void invalidPattern_throwsException() {
        // 无效的正则表达式应该在编译时抛出异常
        Pattern.compile("[invalid");
    }

    @Test(expected = PatternSyntaxException.class)
    public void unclosedGroupPattern_throwsException() {
        // 未闭合的分组应该在编译时抛出异常
        Pattern.compile("(abc");
    }

    @Test
    public void exactMatch_matchesCorrectly() {
        // 精确匹配
        Pattern pattern = Pattern.compile("^hello$");
        Assert.assertTrue(evaluator.compile(new RegexCriterion(pattern), null).test(MatchContext.of("hello")));
        Assert.assertFalse(evaluator.compile(new RegexCriterion(pattern), null).test(MatchContext.of("hello world")));
        Assert.assertFalse(evaluator.compile(new RegexCriterion(pattern), null).test(MatchContext.of("say hello")));
    }

    @Test
    public void partialMatch_requiresFullMatch() {
        // 需要完全匹配（非部分匹配）
        Pattern helloPattern = Pattern.compile("hello");
        Pattern containsHelloPattern = Pattern.compile(".*hello.*");
        Assert.assertTrue(evaluator.compile(new RegexCriterion(helloPattern), null).test(MatchContext.of("hello")));
        // 部分匹配不成功，需要使用 .* 包围
        Assert.assertFalse(evaluator.compile(new RegexCriterion(helloPattern), null).test(MatchContext.of("say hello world")));
        // 使用 .* 可以实现部分匹配
        Assert.assertTrue(
                evaluator.compile(new RegexCriterion(containsHelloPattern), null).test(MatchContext.of("say hello world")));
    }

    @Test
    public void caseInsensitiveFlag_matchesCorrectly() {
        // 带有忽略大小写标志的正则
        Pattern pattern = Pattern.compile("hello", Pattern.CASE_INSENSITIVE);
        Assert.assertTrue(evaluator.compile(new RegexCriterion(pattern), null).test(MatchContext.of("HELLO")));
        Assert.assertTrue(evaluator.compile(new RegexCriterion(pattern), null).test(MatchContext.of("Hello")));
        Assert.assertTrue(evaluator.compile(new RegexCriterion(pattern), null).test(MatchContext.of("hElLo")));
    }

    @Test
    public void nonStringActual_convertsToString() {
        // actual 为非字符串类型时，会通过 Convert.toStr 转换
        Assert.assertTrue(evaluator.compile(new RegexCriterion(Pattern.compile("^123$")), null).test(MatchContext.of(123)));
        Assert.assertTrue(
                evaluator.compile(new RegexCriterion(Pattern.compile("^\\d+\\.\\d+$")), null).test(MatchContext.of(123.45)));
        Assert.assertTrue(evaluator.compile(new RegexCriterion(Pattern.compile("^true$")), null).test(MatchContext.of(true)));
    }
}
