package com.team4u.framework.base.util;

import java.lang.reflect.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 反射工具类
 * <p>
 * 提供对类、方法、字段进行反射操作的便捷方法。
 *
 * @author jay.wu
 */
public class ReflectUtil {

    /**
     * 字段缓存，用于提高反射获取字段的性能
     */
    private static final Map<Class<?>, Map<String, Field>> FIELD_CACHE = new ConcurrentHashMap<>();

    /**
     * 设置对象字段值
     * <p>
     * 会自动调用 {@link #makeAccessible(AccessibleObject)} 以确保私有字段可访问。
     *
     * @param obj   目标对象
     * @param field 目标字段
     * @param value 待设置的值
     * @throws RuntimeException 当反射设置失败时抛出异常
     */
    public static void setFieldValue(Object obj, Field field, Object value) {
        if (obj == null || field == null) {
            return;
        }
        makeAccessible(field);
        try {
            field.set(obj, value);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Could not set field value", e);
        }
    }

    /**
     * 根据字段名设置对象字段值
     * <p>
     * 会向上递归查找父类中的字段，并确保其可访问。
     *
     * @param obj       目标对象
     * @param fieldName 字段名称
     * @param value     待设置的值
     */
    public static void setFieldValue(Object obj, String fieldName, Object value) {
        if (obj == null || StringUtil.isEmpty(fieldName)) {
            return;
        }
        Field field = getField(obj.getClass(), fieldName);
        if (field != null) {
            setFieldValue(obj, field, value);
        }
    }

    /**
     * 查找类中的指定字段
     * <p>
     * 查找范围包括私有字段，并会向上递归查找父类，直到找到为止。
     *
     * @param clazz     目标类
     * @param fieldName 字段名称
     * @return 字段对象，如果未查找到则返回 null
     */
    public static Field getField(Class<?> clazz, String fieldName) {
        if (clazz == null || StringUtil.isEmpty(fieldName)) {
            return null;
        }

        // 从缓存中获取类的所有字段，如果不存在则进行初始化解析
        Map<String, Field> fieldMap = FIELD_CACHE.computeIfAbsent(clazz, c -> {
            Map<String, Field> map = new HashMap<>();
            Class<?> searchType = c;
            while (searchType != null && searchType != Object.class) {
                for (Field field : searchType.getDeclaredFields()) {
                    // 统一前置设置可访问性
                    makeAccessible(field);
                    // 子类字段优先，避免被父类的同名字段覆盖
                    map.putIfAbsent(field.getName(), field);
                }
                searchType = searchType.getSuperclass();
            }
            return map;
        });

        return fieldMap.get(fieldName);
    }

    /**
     * 调用对象的方法
     * <p>
     * 会自动调用 {@link #makeAccessible(AccessibleObject)} 以确保私有方法可执行。
     *
     * @param obj    目标对象，静态方法可传 null
     * @param method 待调用的方法
     * @param args   调用参数
     * @return 方法执行结果
     * @throws RuntimeException 当方法执行异常或反射调用失败时抛出异常
     */
    public static Object invoke(Object obj, Method method, Object... args) {
        if (method == null) {
            return null;
        }
        makeAccessible(method);
        try {
            return method.invoke(obj, args);
        } catch (InvocationTargetException e) {
            Throwable targetException = e.getTargetException();
            if (targetException instanceof RuntimeException) {
                throw (RuntimeException) targetException;
            }
            if (targetException instanceof Error) {
                throw (Error) targetException;
            }
            throw new RuntimeException("Target method execution failed", targetException);
        } catch (Exception e) {
            throw new RuntimeException("Could not invoke method", e);
        }
    }

    /**
     * 查找类中的指定方法
     * <p>
     * 查找范围包括私有方法，并会向上递归查找父类。
     * 匹配逻辑基于方法名和参数类型的兼容性。
     *
     * @param clazz      目标类
     * @param methodName 方法名称
     * @param paramTypes 方法参数类型
     * @return 方法对象，如果未查找到则返回 null
     */
    public static Method getMethod(Class<?> clazz, String methodName, Class<?>... paramTypes) {
        if (clazz == null || StringUtil.isEmpty(methodName)) {
            return null;
        }
        Class<?> searchType = clazz;
        while (searchType != null) {
            Method[] methods = (searchType.isInterface() ? searchType.getMethods() : searchType.getDeclaredMethods());
            for (Method method : methods) {
                if (methodName.equals(method.getName())
                        && (paramTypes == null || isArgumentsMatch(method.getParameterTypes(), paramTypes))) {
                    return method;
                }
            }
            searchType = searchType.getSuperclass();
        }
        return null;
    }

    /**
     * 检查参数类型列表是否匹配
     *
     * @param parameterTypes 方法定义的参数类型
     * @param paramTypes     待匹配的参数类型
     * @return 如果所有位置的参数类型都兼容则返回 true
     */
    private static boolean isArgumentsMatch(Class<?>[] parameterTypes, Class<?>[] paramTypes) {
        if (parameterTypes.length != paramTypes.length) {
            return false;
        }
        for (int i = 0; i < parameterTypes.length; i++) {
            if (!parameterTypes[i].isAssignableFrom(paramTypes[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * 实例化指定类
     * <p>
     * 使用默认的无参构造函数进行实例化。
     *
     * @param clazz 目标类
     * @param <T>   泛型类型
     * @return 实例化后的对象
     * @throws RuntimeException 当实例化失败时抛出异常
     */
    public static <T> T newInstance(Class<T> clazz) {
        if (clazz == null) {
            return null;
        }
        try {
            return clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Could not instantiate class", e);
        }
    }

    /**
     * 设置可访问性
     * <p>
     * 对于非公开（public）的成员，将其 accessible 标志设置为 true，以允许私有访问。
     *
     * @param accessibleObject 待设置的可访问对象
     */
    public static void makeAccessible(AccessibleObject accessibleObject) {
        if (accessibleObject == null) {
            return;
        }
        if (accessibleObject instanceof Member && !Modifier.isPublic(((Member) accessibleObject).getModifiers())) {
            accessibleObject.setAccessible(true);
        }
    }
}
