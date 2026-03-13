package com.team4u.framework.base.util;

import org.junit.Assert;
import org.junit.Test;

/**
 * TypeUtil 单元测试
 *
 * @author jay.wu
 */
public class TypeUtilTest {

    @Test
    public void testGetTypeArgument() {
        // 测试获取泛型参数
        Class<?> argument = TypeUtil.getTypeArgument(Sub.class);
        Assert.assertEquals("识别出的泛型参数应为 String", String.class, argument);
    }

    @Test
    public void testGetTypeArgumentWithRawType() {
        // 测试原始类型获取泛型参数，应返回 null
        Class<?> argument = TypeUtil.getTypeArgument(RawSub.class);
        Assert.assertNull("未定义泛型时应返回 null", argument);
    }

    @Test
    public void testGetTypeArgumentWithNormalClass() {
        // 测试普通类获取泛型参数，应返回 null
        Class<?> argument = TypeUtil.getTypeArgument(Object.class);
        Assert.assertNull("普通类应返回 null", argument);
    }

    // 定义带泛型的父类
    static class Base<T> {
    }

    // 子类显式声明泛型为 String
    static class Sub extends Base<String> {
    }

    // 子类未声明具体泛型
    static class RawSub extends Base {
    }
}
