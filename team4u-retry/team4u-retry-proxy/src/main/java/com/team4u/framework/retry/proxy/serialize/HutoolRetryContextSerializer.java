package com.team4u.framework.retry.proxy.serialize;

import cn.hutool.core.convert.Convert;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.team4u.framework.retry.exception.RetrySerializationException;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 基于 Hutool 的 {@link JSONUtil} 实现的重试上下文序列化器。
 * <p>
 * 该序列化器主要用于重试场景中的参数持久化与恢复，支持基本类型、包装类、枚举、
 * 集合以及标准的 POJO 对象。通过反射机制处理了泛型集合与复杂对象的嵌套关系。
 */
public class HutoolRetryContextSerializer implements RetryContextSerializer {

    /**
     * 该实例线程安全，提供全局共享访问。
     */
    public static final HutoolRetryContextSerializer INSTANCE = new HutoolRetryContextSerializer();

    /**
     * 将对象序列化为 JSON 字符串。
     * <p>
     * 针对字符序列和枚举类型进行了特殊处理，以确保在不同环境下具有良好的兼容性。
     *
     * @param arg 待序列化的对象，允许为 null
     * @return 对象的 JSON 字符串表示，如果输入为 null 则返回 null
     * @throws RetrySerializationException 序列化过程中发生异常时抛出
     */
    @Override
    public String serialize(Object arg) throws RetrySerializationException {
        if (arg == null) {
            return null;
        }

        try {
            // 特殊处理字符与枚举，确保直接存储其字符串表示，避免部分 JSON 库产生的额外引号或元数据
            if (arg instanceof Character || arg instanceof CharSequence) {
                return JSONUtil.toJsonStr(String.valueOf(arg));
            }
            if (arg instanceof java.lang.Enum<?>) {
                return JSONUtil.toJsonStr(((java.lang.Enum<?>) arg).name());
            }
            return JSONUtil.toJsonStr(arg);
        } catch (Exception e) {
            throw new RetrySerializationException(
                    "序列化重试参数失败。类型: " + arg.getClass().getName()
                            + ", 错误原因: " + e.getMessage(),
                    e);
        }
    }

    /**
     * 将 JSON 字符串反序列化为指定类型的对象。
     * <p>
     * 支持泛型信息提取，能够处理 List&lt;T&gt; 等集合类型及其内部元素的正确实例化。
     *
     * @param declaredType 目标类型的反射描述符
     * @param json         待解析的 JSON 字符串
     * @return 反序列化后的对象实例
     * @throws RetrySerializationException 当类型不支持或解析失败时抛出
     */
    @Override
    public Object deserialize(Type declaredType, String json) throws RetrySerializationException {
        if (json == null) {
            return null;
        }

        try {
            Class<?> rawType = extractRawType(declaredType);
            // 优先处理基础及常用简单类型，以提升解析效率
            if (isBasicType(rawType)) {
                return deserializeBasicValue(rawType, json);
            }
            // 专门处理带泛型信息的集合类，确保内部元素的类型准确性
            if (declaredType instanceof ParameterizedType
                    && Collection.class.isAssignableFrom(rawType)) {
                return deserializeCollection((ParameterizedType) declaredType, json);
            }
            // 处理标准的 JavaBean 或 POJO 复杂对象
            return JSONUtil.toBean(json, declaredType, false);
        } catch (Exception e) {
            throw new RetrySerializationException(
                    "反序列化重试参数失败。目标类型: " + declaredType.getTypeName()
                            + ", 错误原因: " + e.getMessage(),
                    e);
        }
    }

    /**
     * 判断给定类型是否属于基础简单类型。
     * <p>
     * 简单类型通常不需要深层解析，直接通过基础转换器即可处理。
     *
     * @param type 类型描述
     * @return 符合返回 true，否则返回 false
     */
    private boolean isBasicType(Class<?> type) {
        return type.isPrimitive()
                || type == String.class
                || type == Boolean.class
                || type == Byte.class
                || type == Short.class
                || type == Integer.class
                || type == Long.class
                || type == Float.class
                || type == Double.class
                || type == Character.class
                || type.isEnum();
    }

    /**
     * 从泛型类型描述符中提取原始类。
     */
    private Class<?> extractRawType(Type declaredType) {
        if (declaredType instanceof Class<?>) {
            return (Class<?>) declaredType;
        }
        if (declaredType instanceof ParameterizedType) {
            Type rawType = ((ParameterizedType) declaredType).getRawType();
            if (rawType instanceof Class<?>) {
                return (Class<?>) rawType;
            }
        }
        throw new IllegalArgumentException("暂不支持处理的类型定义: " + declaredType);
    }

    /**
     * 执行基础简单值的转换逻辑。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object deserializeBasicValue(Class<?> rawType, String json) {
        // 利用数组嵌套方式解析原始 JSON 值，增强解析器的兼容性（适配带或不带引号的情况）
        Object value = JSONUtil.parseArray("[" + json + "]").get(0);
        if (rawType == char.class || rawType == Character.class) {
            String text = Convert.toStr(value);
            return text == null || text.isEmpty() ? '\0' : text.charAt(0);
        }
        if (rawType.isEnum()) {
            return Enum.valueOf((Class) rawType, Convert.toStr(value));
        }
        return Convert.convert(rawType, value);
    }

    /**
     * 递归处理集合对象的反序列化。
     */
    private Collection<Object> deserializeCollection(ParameterizedType declaredType, String json) {
        JSONArray array = JSONUtil.parseArray(json);
        Type elementType = declaredType.getActualTypeArguments()[0];
        Collection<Object> values = createSupportedCollection(extractRawType(declaredType));
        for (Object item : array) {
            values.add(processGenericValue(elementType, item));
        }
        return values;
    }

    /**
     * 根据泛型上下文处理解析后的值，可能涉及多级解嵌套。
     */
    private Object processGenericValue(Type declaredType, Object parsedValue) {
        if (parsedValue == null) {
            return null;
        }
        Class<?> rawType = extractRawType(declaredType);
        if (isBasicType(rawType)) {
            return deserializeBasicValue(rawType, JSONUtil.toJsonStr(parsedValue));
        }
        if (declaredType instanceof ParameterizedType && Collection.class.isAssignableFrom(rawType)) {
            return deserializeCollection((ParameterizedType) declaredType, JSONUtil.toJsonStr(parsedValue));
        }
        if (declaredType instanceof Class<?>) {
            return resolveBeanInstance((Class<?>) declaredType, parsedValue);
        }
        return JSONUtil.toBean(JSONUtil.toJsonStr(parsedValue), declaredType, false);
    }

    /**
     * 系统化处理具体的 Bean 类实例化。
     */
    private Object resolveBeanInstance(Class<?> beanType, Object parsedValue) {
        String json = JSONUtil.toJsonStr(parsedValue);
        try {
            return JSONUtil.toBean(json, beanType);
        } catch (Exception ex) {
            if (parsedValue instanceof JSONObject) {
                JSONObject jsonObject = (JSONObject) parsedValue;
                try {
                    return instantiateViaReflection(beanType, jsonObject);
                } catch (Exception ignored) {
                    return fallbackToParametricConstructor(beanType, jsonObject, ex);
                }
            }
            throw ex;
        }
    }

    /**
     * 通过无参构造函数结合反射属性注入的方式实例化对象。
     */
    private Object instantiateViaReflection(Class<?> beanType, JSONObject jsonObject) throws Exception {
        Constructor<?> constructor = beanType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object instance = constructor.newInstance();
        for (String key : jsonObject.keySet()) {
            Field field = searchFieldRecursively(beanType, key);
            if (field == null) {
                continue;
            }
            field.setAccessible(true);
            Object converted = processGenericValue(field.getGenericType(), jsonObject.get(key));
            field.set(instance, converted);
        }
        return instance;
    }

    /**
     * 兜底方案：尝试通过单参数构造函数进行实例化。
     */
    private Object fallbackToParametricConstructor(
            Class<?> beanType,
            JSONObject jsonObject,
            Exception previousException) {
        for (Constructor<?> constructor : beanType.getDeclaredConstructors()) {
            if (constructor.getParameterTypes().length != 1) {
                continue;
            }
            try {
                constructor.setAccessible(true);
                Object fieldValue = jsonObject.isEmpty() ? null : jsonObject.values().iterator().next();
                Object converted = processGenericValue(constructor.getGenericParameterTypes()[0], fieldValue);
                return constructor.newInstance(converted);
            } catch (Exception ignored) {
                // 忽略特定的单参构造尝试，最终若全部失败则抛出原始异常
            }
        }
        throw new IllegalArgumentException(
                "重试反序列化中无法实例化的 Bean 类型: " + beanType.getName(),
                previousException);
    }

    /**
     * 递归搜索类及其父类中的指定字段。
     */
    private Field searchFieldRecursively(Class<?> beanType, String name) {
        Class<?> current = beanType;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /**
     * 根据目标类型创建合适的集合实例。
     */
    private Collection<Object> createSupportedCollection(Class<?> rawType) {
        if (rawType.isAssignableFrom(ArrayList.class) || rawType == Collection.class || rawType == Iterable.class) {
            return new ArrayList<>();
        }
        if (rawType.isAssignableFrom(LinkedHashSet.class)) {
            return new LinkedHashSet<>();
        }
        if (rawType.isArray()) {
            throw new IllegalArgumentException("目前暂不支持数组形式的集合反序列化: " + rawType.getName());
        }
        if (rawType.isInterface()) {
            if (rawType == Set.class) {
                return new LinkedHashSet<>();
            }
            return new ArrayList<>();
        }
        try {
            @SuppressWarnings("unchecked")
            Collection<Object> instance = (Collection<Object>) rawType.getDeclaredConstructor().newInstance();
            return instance;
        } catch (Exception ex) {
            throw new IllegalArgumentException("无法创建目标集合类型的实例: " + rawType.getName(), ex);
        }
    }
}
