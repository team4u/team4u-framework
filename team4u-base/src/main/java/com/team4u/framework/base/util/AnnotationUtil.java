package com.team4u.framework.base.util;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;

/**
 * 注解工具类
 * <p>
 * 提供针对注解的便捷操作，包括获取指定类型的注解以及判断元素是否包含特定注解。
 *
 * @author jay.wu
 */
public class AnnotationUtil {

    /**
     * 获取指定类型的注解
     * <p>
     * 从给定的被注解元素中提取指定类型的注解对象。
     *
     * @param element         支持注解的反射元素，如 Class、Method、Field 等
     * @param annotationClass 目标注解的类类型
     * @param <A>             目标注解的泛型类型
     * @return 匹配的注解实例，若元素为 null、注解类为 null 或元素未被该注解修饰，则返回 null
     */
    public static <A extends Annotation> A getAnnotation(AnnotatedElement element, Class<A> annotationClass) {
        if (element == null || annotationClass == null) {
            return null;
        }
        return element.getAnnotation(annotationClass);
    }

    /**
     * 判断是否存在指定类型的注解
     * <p>
     * 检查给定的被注解元素是否被指定类型的注解所修饰。
     *
     * @param element         支持注解的反射元素，如 Class、Method、Field 等
     * @param annotationClass 待检查的注解类类型
     * @return 如果元素被该注解修饰则返回 true，否则返回 false
     */
    public static boolean hasAnnotation(AnnotatedElement element, Class<? extends Annotation> annotationClass) {
        return getAnnotation(element, annotationClass) != null;
    }
}
