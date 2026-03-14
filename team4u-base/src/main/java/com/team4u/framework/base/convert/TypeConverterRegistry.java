package com.team4u.framework.base.convert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 类型转换器注册表
 *
 * @author jay.wu
 */
public class TypeConverterRegistry {

    private static final Logger log = LoggerFactory.getLogger(TypeConverterRegistry.class);

    /**
     * 表示未匹配到合适转换器的占位对象
     */
    private static final Object NO_MATCH = new Object();

    /**
     * 转换器排序规则
     */
    private static final Comparator<TypeConverter> ORDER_COMPARATOR =
            Comparator.comparingInt(TypeConverter::order);

    /**
     * 内置转换器列表
     */
    private final List<TypeConverter> builtInConverters = new ArrayList<>();
    /**
     * 自定义转换器列表，使用线程安全容器
     */
    private final CopyOnWriteArrayList<TypeConverter> customConverters = new CopyOnWriteArrayList<>();

    /**
     * 重置转换器，恢复默认内置转换器并清除自定义转换器
     */
    public synchronized void resetDefaults() {
        builtInConverters.clear();
        builtInConverters.add(new ScalarTypeConverter());
        builtInConverters.add(new EnumTypeConverter());
        builtInConverters.add(new TemporalTypeConverter());
        builtInConverters.add(new CollectionTypeConverter());
        builtInConverters.add(new ArrayTypeConverter());
        builtInConverters.add(new BeanTypeConverter());
        builtInConverters.sort(ORDER_COMPARATOR);
        customConverters.clear();
    }

    /**
     * 注册自定义类型转换器
     *
     * @param converter 类型转换器实例
     */
    public synchronized void register(TypeConverter converter) {
        if (converter == null) {
            return;
        }
        customConverters.add(converter);
        List<TypeConverter> sorted = new ArrayList<>(customConverters);
        sorted.sort(ORDER_COMPARATOR);
        customConverters.clear();
        customConverters.addAll(sorted);
    }

    /**
     * 移除指定类型的自定义转换器
     *
     * @param converterType 转换器类类型
     */
    public synchronized void remove(Class<? extends TypeConverter> converterType) {
        if (converterType == null) {
            return;
        }
        customConverters.removeIf(converter -> converter.getClass() == converterType);
    }

    /**
     * 获取所有内置转换器
     *
     * @return 不可变的内置转换器列表
     */
    public List<TypeConverter> getBuiltInConverters() {
        return Collections.unmodifiableList(builtInConverters);
    }

    /**
     * 查找合适的转换器并执行转换
     *
     * @param targetType 目标类型
     * @param source     原始值
     * @return 转换结果，匹配失败返回 null
     */
    public Object convert(Type targetType, Object source) {
        if (targetType == null || source == null) {
            return null;
        }
        for (TypeConverter converter : customConverters) {
            Object result = tryConvert(converter, targetType, source);
            if (result != NO_MATCH) {
                return result;
            }
        }
        for (TypeConverter converter : builtInConverters) {
            Object result = tryConvert(converter, targetType, source);
            if (result != NO_MATCH) {
                return result;
            }
        }
        return null;
    }

    /**
     * 尝试使用特定转换器进行转换
     *
     * @param converter  转换器实例
     * @param targetType 目标类型
     * @param source     原始值
     * @return 转换结果，如果不支持则返回 NO_MATCH，转换异常返回 null
     */
    private Object tryConvert(TypeConverter converter, Type targetType, Object source) {
        if (!converter.supports(targetType, source)) {
            return NO_MATCH;
        }
        try {
            return converter.convert(targetType, source);
        } catch (Exception e) {
            log.warn("Type conversion failed, converter={}, targetType={}, sourceType={}, source={}",
                    converter.getClass().getName(),
                    targetType == null ? "null" : targetType.getTypeName(),
                    source == null ? "null" : source.getClass().getName(),
                    summarizeSource(source),
                    e);
            throw new TypeConversionException(converter, targetType, source, e);
        }
    }

    private String summarizeSource(Object source) {
        if (source == null) {
            return "null";
        }
        String value = String.valueOf(source);
        if (value.length() <= 120) {
            return value;
        }
        return value.substring(0, 117) + "...";
    }
}
