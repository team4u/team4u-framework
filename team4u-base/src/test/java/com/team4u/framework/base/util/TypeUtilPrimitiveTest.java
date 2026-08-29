package com.team4u.framework.base.util;

import org.junit.Assert;
import org.junit.Test;

/**
 * TypeUtil 新增能力单元测试（基本类型映射、包装转换、默认值）
 *
 * @author jay.wu
 */
public class TypeUtilPrimitiveTest {

    @Test
    public void forNameResolvesPrimitives() throws Exception {
        Assert.assertEquals("int 解析失败", int.class, TypeUtil.forName("int"));
        Assert.assertEquals("boolean 解析失败", boolean.class, TypeUtil.forName("boolean"));
        Assert.assertEquals("void 解析失败", void.class, TypeUtil.forName("void"));
        Assert.assertEquals("long 解析失败", long.class, TypeUtil.forName("long"));
        Assert.assertEquals("double 解析失败", double.class, TypeUtil.forName("double"));
    }

    @Test
    public void forNameResolvesOrdinaryClasses() throws Exception {
        Assert.assertEquals("普通类解析失败", String.class, TypeUtil.forName("java.lang.String"));
        Assert.assertEquals("数组类解析失败", String[].class, TypeUtil.forName("[Ljava.lang.String;"));
    }

    @Test(expected = ClassNotFoundException.class)
    public void forNameRejectsUnknown() throws Exception {
        TypeUtil.forName("no.such.ClassName");
    }

    @Test
    public void wrapPrimitives() {
        Assert.assertEquals("boolean 装箱失败", Boolean.class, TypeUtil.wrap(boolean.class));
        Assert.assertEquals("long 装箱失败", Long.class, TypeUtil.wrap(long.class));
        Assert.assertEquals("float 装箱失败", Float.class, TypeUtil.wrap(float.class));
        Assert.assertEquals("double 装箱失败", Double.class, TypeUtil.wrap(double.class));
        Assert.assertEquals("char 装箱失败", Character.class, TypeUtil.wrap(char.class));
        Assert.assertEquals("byte 装箱失败", Byte.class, TypeUtil.wrap(byte.class));
        Assert.assertEquals("short 装箱失败", Short.class, TypeUtil.wrap(short.class));
        Assert.assertEquals("int 装箱失败", Integer.class, TypeUtil.wrap(int.class));
        Assert.assertEquals("void 装箱失败", Void.class, TypeUtil.wrap(void.class));
        // 非基本类型原样返回
        Assert.assertEquals("非基本类型应原样返回", String.class, TypeUtil.wrap(String.class));
        Assert.assertSame("包装类型应原样返回", Integer.class, TypeUtil.wrap(Integer.class));
    }

    @Test
    public void unwrapWrappers() {
        Assert.assertEquals("Boolean 拆箱失败", boolean.class, TypeUtil.unwrap(Boolean.class));
        Assert.assertEquals("Long 拆箱失败", long.class, TypeUtil.unwrap(Long.class));
        Assert.assertEquals("Integer 拆箱失败", int.class, TypeUtil.unwrap(Integer.class));
        Assert.assertEquals("Character 拆箱失败", char.class, TypeUtil.unwrap(Character.class));
        // 非包装类型原样返回
        Assert.assertEquals("非包装类型应原样返回", String.class, TypeUtil.unwrap(String.class));
        Assert.assertSame("基本类型应原样返回", int.class, TypeUtil.unwrap(int.class));
    }

    @Test
    public void wrapUnwrapRoundTrip() {
        Class<?>[] primitives = {boolean.class, byte.class, char.class, short.class,
                int.class, long.class, float.class, double.class};
        for (Class<?> primitive : primitives) {
            Class<?> wrapped = TypeUtil.wrap(primitive);
            Assert.assertEquals("wrap/unwrap 应往返一致", primitive, TypeUtil.unwrap(wrapped));
        }
    }

    @Test
    public void defaultValueOfPrimitives() {
        Assert.assertEquals("boolean 默认值应为 false", Boolean.FALSE, TypeUtil.defaultValueOf(boolean.class));
        Assert.assertEquals("int 默认值应为 0", Integer.valueOf(0), TypeUtil.defaultValueOf(int.class));
        Assert.assertEquals("long 默认值应为 0", Long.valueOf(0L), TypeUtil.defaultValueOf(long.class));
        Assert.assertEquals("double 默认值应为 0", Double.valueOf(0D), TypeUtil.defaultValueOf(double.class));
        Assert.assertEquals("float 默认值应为 0", Float.valueOf(0F), TypeUtil.defaultValueOf(float.class));
        Assert.assertEquals("char 默认值应为 \\u0000", Character.valueOf('\u0000'),
                TypeUtil.defaultValueOf(char.class));
        Assert.assertEquals("byte 默认值应为 0", Byte.valueOf((byte) 0), TypeUtil.defaultValueOf(byte.class));
        Assert.assertEquals("short 默认值应为 0", Short.valueOf((short) 0), TypeUtil.defaultValueOf(short.class));
        Assert.assertNull("void 默认值应为 null", TypeUtil.defaultValueOf(void.class));
    }

    @Test
    public void defaultValueOfNonPrimitives() {
        Assert.assertNull("对象类型默认值应为 null", TypeUtil.defaultValueOf(String.class));
        Assert.assertNull("null 类型默认值应为 null", TypeUtil.defaultValueOf(null));
    }

    @Test
    public void defaultValueIsAssignableToPrimitive() throws Exception {
        // 反射调用兼容性：默认值应可赋给对应基本类型参数
        java.lang.reflect.Method method = PrimitiveHolder.class.getMethod("scale", int.class);
        Object result = method.invoke(new PrimitiveHolder(), TypeUtil.defaultValueOf(int.class));
        Assert.assertEquals("默认值应可参与反射调用", 0, result);
    }

    /**
     * 反射调用辅助测试 bean
     */
    public static class PrimitiveHolder {
        public int scale(int input) {
            return input;
        }
    }
}
