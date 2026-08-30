package com.team4u.framework.base.util;

import org.junit.Assert;
import org.junit.Test;

/**
 * NumberUtil 单元测试
 *
 * @author jay.wu
 */
public class NumberUtilTest {

    @Test
    public void parseDouble() {
        Assert.assertEquals("正常浮点数解析失败", 1.23, NumberUtil.parseDouble("1.23"), 0);
        Assert.assertEquals("非数字字符串应返回默认值 0", 0, NumberUtil.parseDouble("abc"), 0);
        Assert.assertEquals("null 字符串应返回默认值 0", 0, NumberUtil.parseDouble(null), 0);
    }

    @Test
    public void parseInt() {
        Assert.assertEquals("正常整数解析失败", 123, NumberUtil.parseInt("123"));
        Assert.assertEquals("浮点数字符串解析为整数应返回默认值 0", 0, NumberUtil.parseInt("1.23"));
        Assert.assertEquals("非数字字符串应返回默认值 0", 0, NumberUtil.parseInt("abc"));
        Assert.assertEquals("null 字符串应返回默认值 0", 0, NumberUtil.parseInt(null));
    }

    @Test
    public void isNumber() {
        Assert.assertTrue("整数应判定为数字", NumberUtil.isNumber("123"));
        Assert.assertTrue("浮点数应判定为数字", NumberUtil.isNumber("1.23"));
        Assert.assertTrue("负数应判定为数字", NumberUtil.isNumber("-1.23"));
        Assert.assertFalse("字母应判定为非数字", NumberUtil.isNumber("abc"));
        Assert.assertFalse("空字符串应判定为非数字", NumberUtil.isNumber(""));
        Assert.assertFalse("null 应判定为非数字", NumberUtil.isNumber(null));
    }

    @Test
    public void isInteger() {
        Assert.assertTrue("正整数应判定为整数", NumberUtil.isInteger("123"));
        Assert.assertTrue("负整数应判定为整数", NumberUtil.isInteger("-123"));
        Assert.assertFalse("浮点数不应判定为整数", NumberUtil.isInteger("1.23"));
        Assert.assertFalse("字母不应判定为整数", NumberUtil.isInteger("abc"));
        Assert.assertFalse("空字符串不应判定为整数", NumberUtil.isInteger(""));
        Assert.assertFalse("null 不应判定为整数", NumberUtil.isInteger(null));
    }
}
