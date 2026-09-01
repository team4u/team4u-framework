package com.team4u.framework.base.convert;

import java.lang.reflect.Array;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通用类型转换工具类
 * <p>
 * 提供字符串、数值、布尔值、集合以及 Bean 之间的灵活转换。
 *
 * @author jay.wu
 */
public class ConvertUtil {

    private static final Pattern DURATION_PATTERN = Pattern.compile(
            "^([+-]?\\d+(?:\\.\\d+)?)\\s*(ns|us|µs|ms|s|m|h|d)$", Pattern.CASE_INSENSITIVE);

    private static final TypeConverterRegistry REGISTRY = new TypeConverterRegistry();

    static {
        REGISTRY.resetDefaults();
    }

    /**
     * 注册自定义类型转换器
     *
     * @param converter 类型转换器实例
     */
    public static void registerConverter(TypeConverter converter) {
        REGISTRY.register(converter);
    }

    /**
     * 移除指定类型的转换器
     *
     * @param converterType 转换器类类型
     */
    public static void removeConverter(Class<? extends TypeConverter> converterType) {
        REGISTRY.remove(converterType);
    }

    /**
     * 重置为默认转换器，清除所有自定义转换器
     */
    public static void resetDefaultConverters() {
        REGISTRY.resetDefaults();
    }

    /**
     * 转换对象到指定类型
     *
     * @param type  目标类型
     * @param value 原始值
     * @param <T>   泛型目标类型
     * @return 转换后的值，转换失败返回 null
     */
    public static <T> T convert(Type type, Object value) {
        return convert(type, value, null);
    }

    /**
     * 转换对象到指定类型，支持默认值
     *
     * @param type         目标类型
     * @param value        原始值
     * @param defaultValue 转换失败或原始值为 null 时返回的默认值
     * @param <T>          泛型目标类型
     * @return 转换后的值
     */
    @SuppressWarnings("unchecked")
    public static <T> T convert(Type type, Object value, T defaultValue) {
        if (value == null || type == null) {
            return defaultValue;
        }
        if (type instanceof Class && ((Class<?>) type).isInstance(value)) {
            return (T) value;
        }
        Object result = REGISTRY.convert(type, value);
        return result != null ? (T) result : defaultValue;
    }

    /**
     * 转换对象到指定 Class 类型
     *
     * @param type  目标 Class 类型
     * @param value 原始值
     * @param <T>   泛型目标类型
     * @return 转换后的值，转换失败返回 null
     */
    public static <T> T convert(Class<T> type, Object value) {
        return convert((Type) type, value, null);
    }

    /**
     * 转换对象到指定 Class 类型，支持默认值
     *
     * @param type         目标 Class 类型
     * @param value        原始值
     * @param defaultValue 转换失败或原始值为 null 时返回的默认值
     * @param <T>          泛型目标类型
     * @return 转换后的值
     */
    public static <T> T convert(Class<T> type, Object value, T defaultValue) {
        return convert((Type) type, value, defaultValue);
    }

    /**
     * 将对象转换为指定元素类型的列表
     *
     * @param value       原始值
     * @param elementType 列表元素类型
     * @param <T>         列表元素泛型
     * @return 转换后的列表，原始值为 null 时返回空列表
     */
    public static <T> List<T> toList(Object value, Class<T> elementType) {
        if (value == null) {
            return Collections.emptyList();
        }
        List<?> rawList = toList(value);
        List<T> result = new ArrayList<>(rawList.size());
        for (Object item : rawList) {
            result.add(convert(elementType, item));
        }
        return result;
    }

    /**
     * 转换为字符串
     *
     * @param value 原始值
     * @return 字符串结果，原始值为 null 时返回 null
     */
    public static String toStr(Object value) {
        return toStr(value, null);
    }

    /**
     * 转换为字符串，支持默认值
     *
     * @param value        原始值
     * @param defaultValue 原始值为 null 时的默认值
     * @return 字符串结果
     */
    public static String toStr(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return value.toString();
    }

    /**
     * 转换为 Long 类型
     *
     * @param value 原始值
     * @return Long 结果，转换失败返回 null
     */
    public static Long toLong(Object value) {
        return toLong(value, null);
    }

    /**
     * 转换为 Long 类型，支持默认值
     *
     * @param value        原始值
     * @param defaultValue 转换失败或为空时的默认值
     * @return Long 结果
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
     * 转换为 Integer 类型
     *
     * @param value 原始值
     * @return Integer 结果，转换失败返回 null
     */
    public static Integer toInt(Object value) {
        return toInt(value, null);
    }

    /**
     * 转换为 Integer 类型，支持默认值
     *
     * @param value        原始值
     * @param defaultValue 转换失败或为空时的默认值
     * @return Integer 结果
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
     * 转换为 Double 类型
     *
     * @param value 原始值
     * @return Double 结果，转换失败返回 null
     */
    public static Double toDouble(Object value) {
        return toDouble(value, null);
    }

    /**
     * 转换为 Double 类型，支持默认值
     *
     * @param value        原始值
     * @param defaultValue 转换失败或为空时的默认值
     * @return Double 结果
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
     * 转换为 Float 类型
     *
     * @param value 原始值
     * @return Float 结果，转换失败返回 null
     */
    public static Float toFloat(Object value) {
        return toFloat(value, null);
    }

    /**
     * 转换为 Float 类型，支持默认值
     *
     * @param value        原始值
     * @param defaultValue 转换失败或为空时的默认值
     * @return Float 结果
     */
    public static Float toFloat(Object value, Float defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Float) {
            return (Float) value;
        }
        if (value instanceof Number) {
            return ((Number) value).floatValue();
        }
        try {
            return Float.parseFloat(value.toString().trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * 转换为 Short 类型
     *
     * @param value 原始值
     * @return Short 结果，转换失败返回 null
     */
    public static Short toShort(Object value) {
        return toShort(value, null);
    }

    /**
     * 转换为 Short 类型，支持默认值
     *
     * @param value        原始值
     * @param defaultValue 转换失败或为空时的默认值
     * @return Short 结果
     */
    public static Short toShort(Object value, Short defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Short) {
            return (Short) value;
        }
        if (value instanceof Number) {
            return ((Number) value).shortValue();
        }
        try {
            return Short.parseShort(value.toString().trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * 转换为 Byte 类型
     *
     * @param value 原始值
     * @return Byte 结果，转换失败返回 null
     */
    public static Byte toByte(Object value) {
        return toByte(value, null);
    }

    /**
     * 转换为 Byte 类型，支持默认值
     *
     * @param value        原始值
     * @param defaultValue 转换失败或为空时的默认值
     * @return Byte 结果
     */
    public static Byte toByte(Object value, Byte defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Byte) {
            return (Byte) value;
        }
        if (value instanceof Number) {
            return ((Number) value).byteValue();
        }
        try {
            return Byte.parseByte(value.toString().trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * 转换为 BigDecimal 类型
     *
     * @param value 原始值
     * @return BigDecimal 结果，转换失败返回 null
     */
    public static BigDecimal toBigDecimal(Object value) {
        return toBigDecimal(value, null);
    }

    /**
     * 转换为 BigDecimal 类型，支持默认值
     *
     * @param value        原始值
     * @param defaultValue 转换失败或为空时的默认值
     * @return BigDecimal 结果
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
     * 转换为 BigInteger 类型
     *
     * @param value 原始值
     * @return BigInteger 结果，转换失败返回 null
     */
    public static BigInteger toBigInteger(Object value) {
        return toBigInteger(value, null);
    }

    /**
     * 转换为 BigInteger 类型，支持默认值
     *
     * @param value        原始值
     * @param defaultValue 转换失败或为空时的默认值
     * @return BigInteger 结果
     */
    public static BigInteger toBigInteger(Object value, BigInteger defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof BigInteger) {
            return (BigInteger) value;
        }
        if (value instanceof Number) {
            return BigInteger.valueOf(((Number) value).longValue());
        }
        try {
            return new BigInteger(value.toString().trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * 转换为 Character 类型
     *
     * @param value 原始值
     * @return Character 结果，转换失败返回 null
     */
    public static Character toChar(Object value) {
        return toChar(value, null);
    }

    /**
     * 转换为 Character 类型，支持默认值
     *
     * @param value        原始值
     * @param defaultValue 转换失败或为空时的默认值
     * @return Character 结果
     */
    public static Character toChar(Object value, Character defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Character) {
            return (Character) value;
        }
        String valueStr = value.toString().trim();
        if (valueStr.isEmpty()) {
            return defaultValue;
        }
        return valueStr.charAt(0);
    }

    /**
     * 转换为 Boolean 类型
     *
     * @param value 原始值
     * @return Boolean 结果，转换失败返回 null
     */
    public static Boolean toBool(Object value) {
        return toBool(value, null);
    }

    /**
     * 转换为 Boolean 类型，支持默认值
     *
     * @param value        原始值
     * @param defaultValue 转换失败或为空时的默认值
     * @return Boolean 结果
     */
    public static Boolean toBool(Object value, Boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        String valueStr = value.toString().trim().toLowerCase();
        if ("true".equals(valueStr) || "1".equals(valueStr) || "yes".equals(valueStr)
                || "ok".equals(valueStr) || "on".equals(valueStr) || "y".equals(valueStr)) {
            return true;
        }
        if ("false".equals(valueStr) || "0".equals(valueStr) || "no".equals(valueStr)
                || "off".equals(valueStr) || "n".equals(valueStr)) {
            return false;
        }
        return defaultValue;
    }

    /**
     * 转换为 Duration 时长类型
     *
     * @param source 原始值，支持 Duration 实例、数值毫秒数、时间单位字符串（如 100ms, 5s, 10m, 1h, 2d）及 ISO-8601 格式（如 PT10S）
     * @return Duration 结果，转换失败返回 null
     */
    public static Duration toDuration(Object source) {
        return toDuration(source, null);
    }

    /**
     * 转换为 Duration 时长类型，支持默认值
     *
     * @param source       原始值，支持 Duration 实例、数值毫秒数、时间单位字符串（如 100ms, 5s, 10m, 1h, 2d）及 ISO-8601 格式（如 PT10S）
     * @param defaultValue 转换失败或为空时的默认值
     * @return Duration 结果
     */
    public static Duration toDuration(Object source, Duration defaultValue) {
        if (source == null) {
            return defaultValue;
        }
        if (source instanceof Duration) {
            return (Duration) source;
        }
        if (source instanceof Number) {
            return Duration.ofMillis(((Number) source).longValue());
        }
        String str = source.toString().trim();
        if (str.isEmpty()) {
            return defaultValue;
        }
        if (str.length() >= 2 && str.startsWith("\"") && str.endsWith("\"")) {
            str = str.substring(1, str.length() - 1).trim();
            if (str.isEmpty()) {
                return defaultValue;
            }
        }
        // 支持 ISO-8601 格式（如 PT10S, P1D）
        if (str.startsWith("P") || str.startsWith("p") || str.startsWith("-P") || str.startsWith("-p") || str.startsWith("+P") || str.startsWith("+p")) {
            try {
                return Duration.parse(str);
            } catch (Exception e) {
                try {
                    return Duration.parse(str.toUpperCase());
                } catch (Exception ignored) {
                    return defaultValue;
                }
            }
        }
        // 支持纯数字字符串（按毫秒解析）
        if (str.matches("^[+-]?\\d+$")) {
            try {
                return Duration.ofMillis(Long.parseLong(str));
            } catch (Exception e) {
                return defaultValue;
            }
        }
        if (str.matches("^[+-]?\\d+\\.\\d+$")) {
            try {
                return Duration.ofMillis((long) Double.parseDouble(str));
            } catch (Exception e) {
                return defaultValue;
            }
        }
        // 支持带时间单位后缀的字符串（100ms, 5s, 10m, 1h, 2d 等，大小写不敏感）
        Matcher matcher = DURATION_PATTERN.matcher(str);
        if (matcher.matches()) {
            try {
                double amount = Double.parseDouble(matcher.group(1));
                String unit = matcher.group(2).toLowerCase();
                switch (unit) {
                    case "ns":
                        return Duration.ofNanos((long) amount);
                    case "us":
                    case "µs":
                        return Duration.ofNanos((long) (amount * 1_000));
                    case "ms":
                        return Duration.ofMillis((long) amount);
                    case "s":
                        return Duration.ofMillis((long) (amount * 1_000));
                    case "m":
                        return Duration.ofMillis((long) (amount * 60_000));
                    case "h":
                        return Duration.ofMillis((long) (amount * 3_600_000));
                    case "d":
                        return Duration.ofMillis((long) (amount * 86_400_000));
                    default:
                        return defaultValue;
                }
            } catch (Exception e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /**
     * 将对象转换为 List
     * <p>
     * 支持 Collection、数组、逗号分隔字符串等。
     *
     * @param value 原始值
     * @param <T>   泛型类型
     * @return 转换后的 List，原始值为 null 时返回空列表
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
        if (value instanceof String) {
            String str = (String) value;
            if (str.contains(",")) {
                String[] parts = str.split(",");
                List<T> list = new ArrayList<>(parts.length);
                for (String part : parts) {
                    list.add((T) part.trim());
                }
                return list;
            }
        }
        List<T> list = new ArrayList<>();
        list.add((T) value);
        return list;
    }

    /**
     * 将对象转换为数组
     *
     * @param componentType 数组元素 Class 类型
     * @param value         原始值
     * @param <T>           数组元素泛型
     * @return 转换后的数组
     */
    public static <T> Object toArray(Class<T> componentType, Object value) {
        if (value == null) {
            return null;
        }
        if (value.getClass().isArray() && value.getClass().getComponentType() == componentType) {
            return value;
        }
        List<?> rawList = toList(value);
        Object array = Array.newInstance(componentType, rawList.size());
        for (int i = 0; i < rawList.size(); i++) {
            Array.set(array, i, convert(componentType, rawList.get(i)));
        }
        return array;
    }
}
