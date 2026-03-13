package com.team4u.framework.base.util;

import org.junit.Assert;
import org.junit.Test;

/**
 * 数组工具类单元测试
 *
 * @author jay.wu
 */
public class ArrayUtilTest {

    @Test
    public void isEmpty() {
        // 测试 null 数组
        Assert.assertTrue("null 数组应为空", ArrayUtil.isEmpty(null));

        // 测试长度为 0 的数组
        Assert.assertTrue("空数组应为空", ArrayUtil.isEmpty(new Object[0]));

        // 测试包含元素的数组
        Assert.assertFalse("非空数组不应为空", ArrayUtil.isEmpty(new Object[]{"test"}));
    }

    @Test
    public void isNotEmpty() {
        // 测试 null 数组
        Assert.assertFalse("null 数组不应为非空", ArrayUtil.isNotEmpty(null));

        // 测试长度为 0 的数组
        Assert.assertFalse("空数组不应为非空", ArrayUtil.isNotEmpty(new Object[0]));

        // 测试包含元素的数组
        Assert.assertTrue("非空数组应为非空", ArrayUtil.isNotEmpty(new Object[]{"test"}));
    }
}
