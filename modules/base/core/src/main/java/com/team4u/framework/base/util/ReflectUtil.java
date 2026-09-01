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
     * 方法数组缓存，用于避免频繁调用获取方法数组时的内存拷贝与对象创建开销
     */
    private static final Map<Class<?>, Method[]> METHOD_CACHE = new ConcurrentHashMap<>();

    /**
     * 方法参数缓存，用于避免频繁调用获取方法参数时的内存拷贝与对象创建开销
     */
    private static final Map<Class<?>, Map<Method, Parameter[]>> PARAMETER_CACHE = new ConcurrentHashMap<>();

    /**
     * 空参数数组占位符，用于在缓存中表示未找到带名称的参数
     */
    private static final Parameter[] EMPTY_PARAMETERS = new Parameter[0];

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
     * 根据字段名获取对象字段值
     * <p>
     * 会向上递归查找父类中的字段，并确保其可访问。
     *
     * @param obj       目标对象
     * @param fieldName 字段名称
     * @return 字段值，未找到时返回 null
     */
    public static Object getFieldValue(Object obj, String fieldName) {
        if (obj == null || StringUtil.isEmpty(fieldName)) {
            return null;
        }
        Field field = getField(obj.getClass(), fieldName);
        if (field == null) {
            return null;
        }
        makeAccessible(field);
        try {
            return field.get(obj);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Could not get field value", e);
        }
    }

    /**
     * 获取类及其所有父类中定义的所有字段映射（字段名 -> Field 对象）
     * <p>
     * 子类同名字段优先，自动前置设置字段为可访问状态，结果带内存缓存。
     *
     * @param clazz 目标类
     * @return 字段映射 Map
     */
    public static Map<String, Field> getFieldMap(Class<?> clazz) {
        if (clazz == null) {
            return java.util.Collections.emptyMap();
        }

        return FIELD_CACHE.computeIfAbsent(clazz, c -> {
            Map<String, Field> map = new java.util.LinkedHashMap<>();
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
    }

    /**
     * 获取类及其所有父类中定义的所有字段列表
     * <p>
     * 自动前置设置字段为可访问状态，结果带内存缓存。
     *
     * @param clazz 目标类
     * @return 字段列表
     */
    public static java.util.List<Field> getFields(Class<?> clazz) {
        if (clazz == null) {
            return java.util.Collections.emptyList();
        }
        return new java.util.ArrayList<>(getFieldMap(clazz).values());
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
        return getFieldMap(clazz).get(fieldName);
    }

    /**
     * 判断字段是否为普通的实例字段（非 static、非 transient、非 synthetic）
     *
     * @param field 待判断的字段
     * @return 如果是有效实例字段则返回 true
     */
    public static boolean isInstanceField(Field field) {
        if (field == null) {
            return false;
        }
        int modifiers = field.getModifiers();
        return !Modifier.isStatic(modifiers) && !Modifier.isTransient(modifiers) && !field.isSynthetic();
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
            Method[] methods = getMethods(searchType);
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
     * 获取类的所有方法，带缓存机制以提升性能
     *
     * @param clazz 目标类
     * @return 方法数组
     */
    private static Method[] getMethods(Class<?> clazz) {
        return METHOD_CACHE.computeIfAbsent(clazz, c ->
                c.isInterface() ? c.getMethods() : c.getDeclaredMethods()
        );
    }

    /**
     * 获取方法参数定义，优先尝试从目标类查找以确保获取真实参数名称
     * <p>
     * 说明：此方法内部增加了缓存支持，以提高获取方法参数的性能，避免 Parameter 对象的频繁创建。
     *
     * @param targetClass 目标类
     * @param method      方法对象
     * @return 参数数组，如果未获取到则返回 null
     */
    public static Parameter[] getParameters(Class<?> targetClass, Method method) {
        if (method == null) {
            return null;
        }

        Map<Method, Parameter[]> methodCache = PARAMETER_CACHE.computeIfAbsent(targetClass, k -> new ConcurrentHashMap<>());

        Parameter[] cachedParams = methodCache.computeIfAbsent(method, m -> {
            Method targetMethod = getMethod(targetClass, m.getName(), m.getParameterTypes());
            if (targetMethod != null) {
                Parameter[] parameters = targetMethod.getParameters();
                if (parameters.length > 0 && parameters[0].isNamePresent()) {
                    return parameters;
                }
            }

            Parameter[] parameters = m.getParameters();
            if (parameters.length > 0 && parameters[0].isNamePresent()) {
                return parameters;
            }

            // 使用占位符表示未找到带名称的参数或无参数
            return EMPTY_PARAMETERS;
        });

        return cachedParams == EMPTY_PARAMETERS ? null : cachedParams;
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
