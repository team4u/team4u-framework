package com.team4u.framework.base.convert;

import java.lang.reflect.Type;

/**
 * 通用类型转换 SPI
 *
 * @author jay.wu
 */
public interface TypeConverter {

    /**
     * 判断当前转换器是否支持指定目标类型
     *
     * @param targetType 目标类型
     * @param source     原始值
     * @return 是否支持
     */
    boolean supports(Type targetType, Object source);

    /**
     * 执行转换
     *
     * @param targetType 目标类型
     * @param source     原始值
     * @return 转换结果，返回 null 视为转换失败或无结果
     */
    Object convert(Type targetType, Object source);

    /**
     * 顺序值，越小优先级越高
     *
     * @return 顺序值
     */
    default int order() {
        return 0;
    }
}