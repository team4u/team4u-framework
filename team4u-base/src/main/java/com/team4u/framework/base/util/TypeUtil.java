package com.team4u.framework.base.util;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

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

    /**
     * 基本类型名 → 基本类型 Class 的映射（含 void.class）
     * <p>
     * {@link Class#forName(String)} 无法解析 "int"、"boolean" 等基本类型名，
     * 序列化/反射场景（如方法参数类型回放）需先查本表再回退 Class.forName。
     * 返回的 Map 不可变。此前 InvocationReplay 持有同款私有实现。
     * </p>
     */
    private static final Map<String, Class<?>> PRIMITIVE_TYPES = buildPrimitiveTypes();

    private static Map<String, Class<?>> buildPrimitiveTypes() {
        Map<String, Class<?>> primitiveTypes = new HashMap<String, Class<?>>();
        primitiveTypes.put(boolean.class.getName(), boolean.class);
        primitiveTypes.put(byte.class.getName(), byte.class);
        primitiveTypes.put(char.class.getName(), char.class);
        primitiveTypes.put(short.class.getName(), short.class);
        primitiveTypes.put(int.class.getName(), int.class);
        primitiveTypes.put(long.class.getName(), long.class);
        primitiveTypes.put(float.class.getName(), float.class);
        primitiveTypes.put(double.class.getName(), double.class);
        primitiveTypes.put(void.class.getName(), void.class);
        return Collections.unmodifiableMap(primitiveTypes);
    }

    /**
     * 按类名解析 Class，支持基本类型名
     * <p>
     * 先查基本类型名表（int/boolean 等 9 种，含 void），未命中再走
     * {@link Class#forName(String)}。适用于反序列化后恢复方法参数类型等
     * “类名来自反射序列化产物”的场景。
     * </p>
     *
     * @param typeName 类全限定名或基本类型名
     * @return 对应的 Class 对象
     * @throws ClassNotFoundException 类名既非基本类型名也找不到对应类
     */
    public static Class<?> forName(String typeName) throws ClassNotFoundException {
        Class<?> primitiveType = PRIMITIVE_TYPES.get(typeName);
        if (primitiveType != null) {
            return primitiveType;
        }
        return Class.forName(typeName);
    }

    /**
     * 基本类型 → 包装类型
     * <p>
     * 非基本类型原样返回。反射调用（Method.invoke 不接受基本类型的 null 参数）与
     * 类型兼容性校验（基本类型按包装类比较）场景常用。此前 SingleFlightInterceptor
     * 持有同款私有实现 box()。
     * </p>
     *
     * @param type 待转换类型
     * @return 对应的包装类型；非基本类型返回自身
     */
    public static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == void.class) {
            return Void.class;
        }
        return Integer.class;
    }

    /**
     * 包装类型 → 基本类型
     * <p>
     * 非包装类型（含基本类型自身与任意对象类型）原样返回。
     * </p>
     *
     * @param type 待转换类型
     * @return 对应的基本类型；非八大包装类型返回自身
     */
    public static Class<?> unwrap(Class<?> type) {
        if (type == Boolean.class) {
            return boolean.class;
        }
        if (type == Long.class) {
            return long.class;
        }
        if (type == Float.class) {
            return float.class;
        }
        if (type == Double.class) {
            return double.class;
        }
        if (type == Character.class) {
            return char.class;
        }
        if (type == Byte.class) {
            return byte.class;
        }
        if (type == Short.class) {
            return short.class;
        }
        if (type == Integer.class) {
            return int.class;
        }
        return type;
    }

    /**
     * 基本类型的默认值（零值），非基本类型返回 null
     * <p>
     * boolean → false，数值类型 → 0，char → '\u0000'，void → null。
     * 代理拦截器在「拒绝执行但需返回方法兼容值」的场景下常用
     * （如限流器 NULL_VALUE 拒绝策略）。此前 RateLimitInterceptor
     * 持有同款私有实现 defaultValueOf()。
     * </p>
     *
     * @param type 目标类型
     * @return 基本类型对应的零值（自动装箱为包装对象）；非基本类型返回 null
     */
    public static Object defaultValueOf(Class<?> type) {
        if (type == null || !type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == long.class) {
            return Long.valueOf(0L);
        }
        if (type == float.class) {
            return Float.valueOf(0F);
        }
        if (type == double.class) {
            return Double.valueOf(0D);
        }
        if (type == char.class) {
            return Character.valueOf('\u0000');
        }
        if (type == byte.class) {
            return Byte.valueOf((byte) 0);
        }
        if (type == short.class) {
            return Short.valueOf((short) 0);
        }
        if (type == void.class) {
            return null;
        }
        return Integer.valueOf(0);
    }
}
