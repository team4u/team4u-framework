package com.team4u.framework.base.convert;
/**
 * 抽象类型转换器基类
 *
 * @author jay.wu
 */
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

abstract class AbstractTypeConverter implements TypeConverter {

    /**
     * 将 Type 转换为 Class
     *
     * @param type 类型对象
     * @return Class 对象，如果无法转换则返回 null
     */
    protected Class<?> toClass(Type type) {
        if (type instanceof Class) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType) {
            Type rawType = ((ParameterizedType) type).getRawType();
            if (rawType instanceof Class) {
                return (Class<?>) rawType;
            }
        }
        return null;
    }
}
