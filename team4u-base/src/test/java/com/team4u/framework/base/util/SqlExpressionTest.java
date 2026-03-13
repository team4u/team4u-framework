package com.team4u.framework.base.util;

import org.junit.Assert;
import org.junit.Test;

/**
 * SqlExpression 单元测试
 *
 * @author jay.wu
 */
public class SqlExpressionTest {

    @Test
    public void testIncrement() {
        // 测试自增表达式生成
        SqlExpression expression = SqlExpression.increment("age");
        Assert.assertEquals("自增表达式内容不正确", "age + 1", expression.getExpression());
    }

    @Test
    public void testPlaceholders() {
        // 测试正常数量的占位符
        Assert.assertEquals("占位符数量不正确", "?,?,?", SqlExpression.placeholders(3));
        // 测试 1 个占位符
        Assert.assertEquals("单个占位符不正确", "?", SqlExpression.placeholders(1));
        // 测试 0 个占位符
        Assert.assertEquals("0 个占位符应返回空字符串", "", SqlExpression.placeholders(0));
        // 测试负数占位符
        Assert.assertEquals("负数占位符应返回空字符串", "", SqlExpression.placeholders(-1));
    }

    @Test
    public void testGetExpression() {
        // 测试获取构造时的原始表达式
        String raw = "id = 1";
        SqlExpression expression = new SqlExpression(raw);
        Assert.assertEquals("获取的原始表达式不正确", raw, expression.getExpression());
    }
}
