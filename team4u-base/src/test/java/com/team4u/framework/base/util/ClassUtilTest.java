package com.team4u.framework.base.util;

import org.junit.Assert;
import org.junit.Test;

import java.net.URI;
import java.net.URL;
import java.time.LocalDate;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

/**
 * 类处理工具类单元测试
 *
 * @author jay.wu
 */
public class ClassUtilTest {

    @Test
    public void loadClass() {
        // 测试正常加载类
        Class<?> clazz = ClassUtil.loadClass("com.team4u.framework.base.util.ClassUtil");
        Assert.assertEquals(ClassUtil.class, clazz);

        // 测试加载不存在的类
        try {
            ClassUtil.loadClass("com.team4u.framework.base.util.NonExistClass");
            Assert.fail("加载不存在的类应抛出异常");
        } catch (RuntimeException e) {
            Assert.assertTrue(e.getMessage().contains("Class not found"));
        }
    }

    @Test
    public void isSimpleValueType() {
        // 基本类型
        Assert.assertTrue(ClassUtil.isSimpleValueType(int.class));
        Assert.assertTrue(ClassUtil.isSimpleValueType(boolean.class));

        // 包装类型
        Assert.assertTrue(ClassUtil.isSimpleValueType(Integer.class));
        Assert.assertTrue(ClassUtil.isSimpleValueType(Boolean.class));

        // 字符串
        Assert.assertTrue(ClassUtil.isSimpleValueType(String.class));

        // 枚举
        Assert.assertTrue(ClassUtil.isSimpleValueType(TestEnum.class));

        // 数字
        Assert.assertTrue(ClassUtil.isSimpleValueType(Long.class));
        Assert.assertTrue(ClassUtil.isSimpleValueType(Double.class));

        // 日期时间
        Assert.assertTrue(ClassUtil.isSimpleValueType(Date.class));
        Assert.assertTrue(ClassUtil.isSimpleValueType(LocalDate.class));

        // URL/URI
        Assert.assertTrue(ClassUtil.isSimpleValueType(URL.class));
        Assert.assertTrue(ClassUtil.isSimpleValueType(URI.class));

        // Locale
        Assert.assertTrue(ClassUtil.isSimpleValueType(Locale.class));

        // Class
        Assert.assertTrue(ClassUtil.isSimpleValueType(Class.class));

        // 非简单类型
        Assert.assertFalse(ClassUtil.isSimpleValueType(Object.class));
        Assert.assertFalse(ClassUtil.isSimpleValueType(ClassUtil.class));
    }

    @Test
    public void scanPackageBySuper() {
        // 扫描当前包下实现 TestInterface 的类
        Set<Class<? extends TestInterface>> classes = ClassUtil.scanPackageBySuper(
                "com.team4u.framework.base.util",
                TestInterface.class
        );

        Assert.assertTrue("应至少扫描到一个实现类", classes.size() >= 1);
        Assert.assertTrue("应包含 TestImplement 类", classes.contains(TestImplement.class));
        Assert.assertFalse("不应包含接口本身", classes.contains(TestInterface.class));
    }

    enum TestEnum {
        A, B
    }

    interface TestInterface {
    }

    static class TestImplement implements TestInterface {
    }
}
