package com.team4u.framework.criterion.util;

import org.junit.Assert;
import org.junit.Test;

public class FastNumberUtilTest {

    @Test
    public void toNumber() {
        Assert.assertNull(FastNumberUtil.toNumber(null));
        Assert.assertNull(FastNumberUtil.toNumber(""));
        Assert.assertNull(FastNumberUtil.toNumber("abc"));
        Assert.assertNull(FastNumberUtil.toNumber("."));
        Assert.assertNull(FastNumberUtil.toNumber("-"));
        Assert.assertNull(FastNumberUtil.toNumber("1.2.3"));

        Assert.assertEquals(123L, FastNumberUtil.toNumber("123"));
        Assert.assertEquals(-123L, FastNumberUtil.toNumber("-123"));
        Assert.assertEquals(123.45D, FastNumberUtil.toNumber("123.45"));
        Assert.assertEquals(-123.45D, FastNumberUtil.toNumber("-123.45"));
        Assert.assertEquals(0.45D, FastNumberUtil.toNumber(".45"));
        Assert.assertEquals(10, FastNumberUtil.toNumber(10));
        Assert.assertEquals(10.5, FastNumberUtil.toNumber(10.5));

        // 科学计数法
        Assert.assertEquals(123000.0D, FastNumberUtil.toNumber("1.23e5"));
        Assert.assertEquals(0.0123D, FastNumberUtil.toNumber("1.23E-2"));

        // 超出 Long 范围的整数，应返回 Double 而不抛异常
        Assert.assertEquals(1.0E20, FastNumberUtil.toNumber("99999999999999999999"));
        Assert.assertEquals(-1.0E20, FastNumberUtil.toNumber("-99999999999999999999"));
        // 18 位整数仍在 Long 范围内
        Assert.assertEquals(9223372036854775807L, FastNumberUtil.toNumber("9223372036854775807"));
    }

    @Test
    public void compare() {
        // 两个 Long
        Assert.assertEquals(0, FastNumberUtil.compare(100L, 100L));
        Assert.assertEquals(-1, FastNumberUtil.compare(99L, 100L));
        Assert.assertEquals(1, FastNumberUtil.compare(101L, 100L));

        // 两个 Integer 转 Long 比较
        Assert.assertEquals(0, FastNumberUtil.compare(100, 100));

        // Long 与 Double 比较
        Assert.assertEquals(0, FastNumberUtil.compare(100L, 100.0D));
        Assert.assertEquals(-1, FastNumberUtil.compare(99L, 100.0D));
        Assert.assertEquals(1, FastNumberUtil.compare(101L, 100.0D));

        // Float 与 Double
        Assert.assertEquals(0, FastNumberUtil.compare(10.5F, 10.5D));
    }

    @Test
    public void isFloatingPoint() {
        Assert.assertTrue(FastNumberUtil.isFloatingPoint(1.0D));
        Assert.assertTrue(FastNumberUtil.isFloatingPoint(1.0F));

        Assert.assertFalse(FastNumberUtil.isFloatingPoint(1L));
        Assert.assertFalse(FastNumberUtil.isFloatingPoint(1));
        Assert.assertFalse(FastNumberUtil.isFloatingPoint((short) 1));
        Assert.assertFalse(FastNumberUtil.isFloatingPoint((byte) 1));
    }
}
