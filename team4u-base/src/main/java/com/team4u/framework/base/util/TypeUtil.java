package com.team4u.framework.base.util;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * 类型反射相关工具类
 * <p>
 * 提供便捷的 Java 反射 API 封装，用于处理泛型参数、类型转换等反射相关的操作。
 * </p>
 *
 * @author jay.wu
 */
public class TypeUtil {

    /**
     * 获取指定类所继承的父类中定义的指定位置泛型参数类型。
     *
     * @param clazz 目标子类
     * @param index 泛型参数位置，从 0 开始
     * @return 识别出的泛型参数类型；若未定义泛型或位置非法则返回 null
     */
    public static Type getTypeArgument(Class<?> clazz, int index) {
        if (clazz == null || index < 0) {
            return null;
        }
        Type type = clazz.getGenericSuperclass();
        if (!(type instanceof ParameterizedType)) {
            return null;
        }
        Type[] arguments = ((ParameterizedType) type).getActualTypeArguments();
        if (index >= arguments.length) {
            return null;
        }
        return arguments[index];
    }

    /**
     * 获取指定类所继承的父类中定义的第一个泛型参数类型
     * <p>
     * 常见场景：在基类中通过此方法获取子类声明的具体泛型类型。
     * 例如：{@code public class Sub extends Base<String>}，在 Base 中调用此方法可获得
     * String.class。
     * </p>
     *
     * @param clazz 目标子类
     * @return 识别出的第一个泛型参数的 Class 对象；若未定义泛型或无法识别则返回 null
     */
    public static Class<?> getTypeArgument(Class<?> clazz) {
        Type argument = getTypeArgument(clazz, 0);
        if (argument instanceof Class) {
            return (Class<?>) argument;
        }
        return null;
    }
}
