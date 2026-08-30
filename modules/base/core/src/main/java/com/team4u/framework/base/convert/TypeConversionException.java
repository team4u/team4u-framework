package com.team4u.framework.base.convert;

import java.lang.reflect.Type;

/**
 * 类型转换执行异常
 *
 * @author jay.wu
 */
public class TypeConversionException extends RuntimeException {

    public TypeConversionException(TypeConverter converter, Type targetType, Object source, Throwable cause) {
        super(buildMessage(converter, targetType, source), cause);
    }

    private static String buildMessage(TypeConverter converter, Type targetType, Object source) {
        String converterName = converter == null ? "unknown" : converter.getClass().getName();
        String targetTypeName = targetType == null ? "null" : targetType.getTypeName();
        String sourceType = source == null ? "null" : source.getClass().getName();
        String sourceSummary = summarizeSource(source);
        return "类型转换失败, 转换器=" + converterName
                + ", 目标类型=" + targetTypeName
                + ", 源类型=" + sourceType
                + ", 源数据=" + sourceSummary;
    }

    private static String summarizeSource(Object source) {
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
