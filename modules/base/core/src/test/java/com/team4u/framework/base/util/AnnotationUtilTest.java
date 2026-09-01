package com.team4u.framework.base.util;

import org.junit.Assert;
import org.junit.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 注解工具类单元测试
 *
 * @author jay.wu
 */
public class AnnotationUtilTest {

    @Test
    public void getAnnotation() {
        // 测试类存在注解的情况
        TestAnnotation annotation = AnnotationUtil.getAnnotation(AnnotatedClass.class, TestAnnotation.class);
        Assert.assertNotNull("类上的注解不应为空", annotation);
        Assert.assertEquals("注解属性值不匹配", "test", annotation.value());

        // 测试方法上存在注解的情况
        try {
            annotation = AnnotationUtil.getAnnotation(
                    AnnotatedClass.class.getMethod("annotatedMethod"),
                    TestAnnotation.class
            );
            Assert.assertNotNull("方法上的注解不应为空", annotation);
            Assert.assertEquals("方法注解属性值不匹配", "method", annotation.value());
        } catch (NoSuchMethodException e) {
            Assert.fail("找不到测试方法");
        }

        // 测试字段上存在注解的情况
        try {
            annotation = AnnotationUtil.getAnnotation(
                    AnnotatedClass.class.getDeclaredField("annotatedField"),
                    TestAnnotation.class
            );
            Assert.assertNotNull("字段上的注解不应为空", annotation);
            Assert.assertEquals("字段注解属性值不匹配", "field", annotation.value());
        } catch (NoSuchFieldException e) {
            Assert.fail("找不到测试字段");
        }

        // 测试不存在注解的情况
        Assert.assertNull("不应获取到不存在的注解", AnnotationUtil.getAnnotation(AnnotatedClass.class, Override.class));

        // 测试元素为 null 的情况
        Assert.assertNull("元素为 null 时应返回 null", AnnotationUtil.getAnnotation(null, TestAnnotation.class));

        // 测试注解类为 null 的情况
        Assert.assertNull("注解类为 null 时应返回 null", AnnotationUtil.getAnnotation(AnnotatedClass.class, null));
    }

    @Test
    public void hasAnnotation() {
        // 测试类存在注解
        Assert.assertTrue("应判断类存在注解", AnnotationUtil.hasAnnotation(AnnotatedClass.class, TestAnnotation.class));

        // 测试类不存在注解
        Assert.assertFalse("应判断类不存在注解", AnnotationUtil.hasAnnotation(AnnotatedClass.class, Override.class));

        // 测试元素为 null
        Assert.assertFalse("元素为 null 时应返回 false", AnnotationUtil.hasAnnotation(null, TestAnnotation.class));
    }

    @Test
    public void findAnnotationOnHierarchy() {
        // 测试子类继承父类上的注解
        TestAnnotation annotation = AnnotationUtil.findAnnotation(ChildClass.class, TestAnnotation.class);
        Assert.assertNotNull("沿着继承链应能找到父类注解", annotation);
        Assert.assertEquals("test", annotation.value());

        Assert.assertTrue("应判断子类在继承链上拥有注解", AnnotationUtil.hasAnnotationOnHierarchy(ChildClass.class, TestAnnotation.class));
        Assert.assertFalse("不应找到不存在的注解", AnnotationUtil.hasAnnotationOnHierarchy(ChildClass.class, Override.class));
        Assert.assertNull("入参为 null 应返回 null", AnnotationUtil.findAnnotation(null, TestAnnotation.class));
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
    @interface TestAnnotation {
        String value() default "";
    }

    @TestAnnotation("test")
    static class AnnotatedClass {

        @TestAnnotation("field")
        private String annotatedField;

        @TestAnnotation("method")
        public void annotatedMethod() {
        }
    }

    static class ChildClass extends AnnotatedClass {
    }
}
