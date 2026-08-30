package com.team4u.framework.base.convert;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * 标量类型转换器
 * <p>
 * 支持基本类型及其包装类、字符串、数字、布尔值及字符类型的转换。
 *
 * @author jay.wu
 */
final class ScalarTypeConverter extends AbstractTypeConverter {

    @Override
    public boolean supports(Type targetType, Object source) {
        Class<?> type = toClass(targetType);
        return type == String.class
                || type == Long.class || type == long.class
                || type == Integer.class || type == int.class
                || type == Double.class || type == double.class
                || type == Float.class || type == float.class
                || type == Short.class || type == short.class
                || type == Byte.class || type == byte.class
                || type == BigDecimal.class
                || type == BigInteger.class
                || type == Boolean.class || type == boolean.class
                || type == Character.class || type == char.class
                || type == Number.class;
    }

    @Override
    public Object convert(Type targetType, Object source) {
        Class<?> type = toClass(targetType);
        if (type == String.class) {
            return ConvertUtil.toStr(source);
        }
        if (type == Long.class || type == long.class) {
            return ConvertUtil.toLong(source);
        }
        if (type == Integer.class || type == int.class) {
            return ConvertUtil.toInt(source);
        }
        if (type == Double.class || type == double.class) {
            return ConvertUtil.toDouble(source);
        }
        if (type == Float.class || type == float.class) {
            return ConvertUtil.toFloat(source);
        }
        if (type == Short.class || type == short.class) {
            return ConvertUtil.toShort(source);
        }
        if (type == Byte.class || type == byte.class) {
            return ConvertUtil.toByte(source);
        }
        if (type == BigDecimal.class || type == Number.class) {
            return ConvertUtil.toBigDecimal(source);
        }
        if (type == BigInteger.class) {
            return ConvertUtil.toBigInteger(source);
        }
        if (type == Boolean.class || type == boolean.class) {
            return ConvertUtil.toBool(source);
        }
        if (type == Character.class || type == char.class) {
            return ConvertUtil.toChar(source);
        }
        return null;
    }

    @Override
    public int order() {
        return 10;
    }
}
