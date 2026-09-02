package com.team4u.framework.criterion.parser.impl;

import com.team4u.framework.criterion.parser.token.Token;
import com.team4u.framework.parser.SourceSpan;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * CriterionLexer 单元测试
 */
public class CriterionLexerTest {

    @Test
    public void testEmptyInput() {
        // 空字符串或空白字符应返回空列表
        assertScan("", Collections.emptyList());
        assertScan("   ", Collections.emptyList());
        assertScan(null, Collections.emptyList());
    }

    @Test
    public void testSimpleIdentifiers() {
        // 基础标识符和操作符
        assertScan("it > 10", Arrays.asList("it", ">", "10"));
        assertScan("user.age == 18", Arrays.asList("user.age", "==", "18"));
    }

    @Test
    public void testNumbers() {
        // 各种数值格式：正负号、整数、浮点数
        assertScan("123 +1 -0.5 3.14", Arrays.asList("123", "+1", "-0.5", "3.14"));
    }

    @Test
    public void testStrings() {
        // 单引号字符串及其转义情况
        assertScan("'hello'", Collections.singletonList("'hello'"));
        assertScan("'It\\'s me'", Collections.singletonList("'It's me'"));
        assertScan("'Path: C:\\\\Temp'", Collections.singletonList("'Path: C:\\Temp'"));
    }

    @Test
    public void testLogicAndDelimiters() {
        // 逻辑运算符和括号、逗号等界定符
        assertScan("&& || ( ) [ ] ,", Arrays.asList("&&", "||", "(", ")", "[", "]", ","));
    }

    @Test
    public void testComplexExpression() {
        // 模拟复杂的组合表达式
        assertScan("user.age >= 18 && (name == 'admin' || role in ['super', 'guest'])",
                Arrays.asList("user.age", ">=", "18", "&&", "(", "name", "==", "'admin'", "||", "role", "in", "[",
                        "'super'", ",", "'guest'", "]", ")"));
    }

    @Test
    public void testSpecialOperators() {
        // 各种组合操作符
        assertScan("!= ~= === ->", Arrays.asList("!=", "~=", "===", "->"));
    }

    @Test
    public void testIdentifierWithSpecialChars() {
        assertScan("user_name $price meta-data v|1",
                Arrays.asList("user_name", "$price", "meta-data", "v|1"));
    }

    @Test
    public void testTokenSourceSpan() {
        String expr = "age >= 18";
        CriterionLexer lexer = new CriterionLexer(expr, "test.rule");
        List<Token> tokens = lexer.scan();

        Assert.assertEquals(3, tokens.size());

        Token t1 = tokens.get(0);
        Assert.assertEquals("age", t1.getValue());
        SourceSpan s1 = t1.getSpan();
        Assert.assertEquals(0, s1.startOffset());
        Assert.assertEquals(3, s1.endOffset());
        Assert.assertEquals(1, s1.startLine());
        Assert.assertEquals(1, s1.startColumn());
        Assert.assertEquals("test.rule", s1.source());

        Token t2 = tokens.get(1);
        Assert.assertEquals(">=", t2.getValue());
        SourceSpan s2 = t2.getSpan();
        Assert.assertEquals(4, s2.startOffset());
        Assert.assertEquals(6, s2.endOffset());
    }

    /**
     * 辅助断言方法：验证分词结果是否符合预期
     */
    private void assertScan(String expression, List<String> expected) {
        CriterionLexer scanner = new CriterionLexer(expression);
        List<String> actual = scanner.scan().stream()
                .map(Token::getValue)
                .collect(Collectors.toList());
        Assert.assertEquals("解析出的 Token 列表与预期不符", expected, actual);
    }
}
