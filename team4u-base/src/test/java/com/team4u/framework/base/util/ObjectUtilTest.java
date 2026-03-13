package com.team4u.framework.base.util;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

/**
 * ObjectUtil 单元测试
 *
 * @author jay.wu
 */
public class ObjectUtilTest {

    @Test
    public void equal() {
        Assert.assertTrue("两个相同的数字应相等", ObjectUtil.equal(1, 1));
        Assert.assertTrue("两个 null 应相等", ObjectUtil.equal(null, null));
        Assert.assertFalse("不同的数字不应相等", ObjectUtil.equal(1, 2));
        Assert.assertFalse("数字与 null 不应相等", ObjectUtil.equal(1, null));
    }

    @Test
    public void defaultIfNull() {
        Assert.assertEquals("非 null 时应返回原值", "a", ObjectUtil.defaultIfNull("a", "b"));
        Assert.assertEquals("为 null 时应返回默认值", "b", ObjectUtil.defaultIfNull(null, "b"));
    }

    @Test
    public void isEmpty() {
        Assert.assertTrue("null 应判定为空", ObjectUtil.isEmpty(null));
        Assert.assertTrue("空字符串应判定为空", ObjectUtil.isEmpty(""));
        Assert.assertTrue("空列表应判定为空", ObjectUtil.isEmpty(new ArrayList<>()));
        Assert.assertTrue("空 Map 应判定为空", ObjectUtil.isEmpty(new HashMap<>()));
        Assert.assertTrue("空数组应判定为空", ObjectUtil.isEmpty(new String[0]));

        Assert.assertFalse("空格字符串不应判定为空", ObjectUtil.isEmpty(" "));
        Assert.assertFalse("非空列表不应判定为空", ObjectUtil.isEmpty(Arrays.asList(1)));
        Assert.assertFalse("非空数组不应判定为空", ObjectUtil.isEmpty(new String[]{"a"}));
        Assert.assertFalse("普通对象不应判定为空", ObjectUtil.isEmpty(123));
    }

    @Test
    public void isNotEmpty() {
        Assert.assertFalse("null 不应判定为非空", ObjectUtil.isNotEmpty(null));
        Assert.assertTrue("非空字符串应判定为非空", ObjectUtil.isNotEmpty("a"));
    }
}
