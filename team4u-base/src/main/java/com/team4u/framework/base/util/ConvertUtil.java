package com.team4u.framework.base.util;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 通用类型转换工具类
 * <p>
 * 提供字符串、数值、布尔值、集合以及 Bean 之间的灵活转换。支持自动识别基础类型的包装类及原始类型。
 *
 * @author jay.wu
 */
public class ConvertUtil {

    /**
     * 通用类型转换
     * <p>
     * 将给定值转换为指定的目标类型。
     *
     * @param type  目标类的 Class 类型
     * @param value 待转换的原始值
     * @param <T>   目标类型泛型
     * @return 转换后的值，若输入为 null 或转换失败且无默认值，则返回 null
     */
    public static <T> T convert(Class<T> type, Object value) {
        return convert(type, value, null);
    }

    /**
     * 通用类型转换（带默认值）
     * <p>
     * 尝试将值转换为目标类型，若转换失败或输入为 null，则返回提供的默认值。
     *
     * @param type         目标类的 Class 类型
     * @param value        待转换的原始值
     * @param defaultValue 转换失败或输入为空时的回退值
     * @param <T>          目标类型泛型
     * @return 转换后的值或默认值
     */
    @SuppressWarnings("unchecked")
    public static <T> T convert(Class<T> type, Object value, T defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (type.isInstance(value)) {
            return (T) value;
        }
        T result = null;
        if (type == String.class) {
            result = (T) toStr(value);
        } else if (type == Long.class || type == long.class) {
            result = (T) toLong(value);
        } else if (type == Integer.class || type == int.class) {
            result = (T) toInt(value);
        } else if (type == Double.class || type == double.class) {
            result = (T) toDouble(value);
        } else if (type == BigDecimal.class) {
            result = (T) toBigDecimal(value);
        } else if (type == Boolean.class || type == boolean.class) {
            result = (T) toBool(value);
        } else if (type == Number.class) {
            result = (T) toBigDecimal(value);
        } else if (value instanceof Map && !Map.class.isAssignableFrom(type)) {
            result = (T) BeanUtil.toBean((Map<?, ?>) value, type, CopyOptions.create());
        }

        return result != null ? result : defaultValue;
    }

    /**
     * 转换为字符串
     *
     * @param value 待转换的值
     * @return 对象的 toString() 结果，若输入为 null 则返回 null
     */
    public static String toStr(Object value) {
        return toStr(value, null);
    }

    /**
     * 转换为字符串（带默认值）
     *
     * @param value        待转换的值
     * @param defaultValue 默认值
     * @return 转换后的字符串
     */
    public static String toStr(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return value.toString();
    }

    /**
     * 转换为长整型 Long
     *
     * @param value 待转换的值
     * @return Long 值，若转换失败则返回 null
     */
    public static Long toLong(Object value) {
        return toLong(value, null);
    }

    /**
     * 转换为长整型 Long（带默认值）
     *
     * @param value        待转换的值
     * @param defaultValue 默认值
     * @return 转换后的 Long
     */
    public static Long toLong(Object value, Long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Long) {
            return (Long) value;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(value.toString().trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * 转换为整型 Integer
     *
     * @param value 待转换的值
     * @return Integer 值，若转换失败则返回 null
     */
    public static Integer toInt(Object value) {
        return toInt(value, null);
    }

    /**
     * 转换为整型 Integer（带默认值）
     *
     * @param value        待转换的值
     * @param defaultValue 默认值
     * @return 转换后的 Integer
     */
    public static Integer toInt(Object value, Integer defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * 转换为双精度浮点型 Double
     *
     * @param value 待转换的值
     * @return Double 值，若转换失败则返回 null
     */
    public static Double toDouble(Object value) {
        return toDouble(value, null);
    }

    /**
     * 转换为双精度浮点型 Double（带默认值）
     *
     * @param value        待转换的值
     * @param defaultValue 默认值
     * @return 转换后的 Double
     */
    public static Double toDouble(Object value, Double defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Double) {
            return (Double) value;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(value.toString().trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * 转换为高精度数值 BigDecimal
     *
     * @param value 待转换的值
     * @return BigDecimal 实例，若转换失败则返回 null
     */
    public static BigDecimal toBigDecimal(Object value) {
        return toBigDecimal(value, null);
    }

    /**
     * 转换为高精度数值 BigDecimal（带默认值）
     *
     * @param value        待转换的值
     * @param defaultValue 默认值
     * @return 转换后的 BigDecimal
     */
    public static BigDecimal toBigDecimal(Object value, BigDecimal defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        try {
            return new BigDecimal(value.toString().trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * 转换为布尔值 Boolean
     * <p>
     * 支持将 "true"、"1"、"yes"、"ok" 识别为 true；"false"、"0"、"no" 识别为 false。
     *
     * @param value 待转换的值
     * @return Boolean 值，若转换失败则返回 null
     */
    public static Boolean toBool(Object value) {
        return toBool(value, null);
    }

    /**
     * 转换为布尔值 Boolean（带默认值）
     *
     * @param value        待转换的值
     * @param defaultValue 默认值
     * @return 转换后的 Boolean
     */
    public static Boolean toBool(Object value, Boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        String valueStr = value.toString().trim().toLowerCase();
        if ("true".equals(valueStr) || "1".equals(valueStr) || "yes".equals(valueStr) || "ok".equals(valueStr)) {
            return true;
        }
        if ("false".equals(valueStr) || "0".equals(valueStr) || "no".equals(valueStr)) {
            return false;
        }
        return defaultValue;
    }

    /**
     * 转换为列表 List
     * <p>
     * 支持将数组、单个对象、集合类型统一转换为 List 包装。
     *
     * @param value 待转换的值
     * @param <T>   列表元素泛型类型
     * @return 包含结果的 List 实例，若输入为 null 则返回空列表（非 null）
     */
    @SuppressWarnings("unchecked")
    public static <T> List<T> toList(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }
        if (value instanceof List) {
            return (List<T>) value;
        }
        if (value instanceof Collection) {
            return new ArrayList<>((Collection<T>) value);
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<T> list = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                list.add((T) Array.get(value, i));
            }
            return list;
        }
        List<T> list = new ArrayList<>();
        list.add((T) value);
        return list;
    }
}
