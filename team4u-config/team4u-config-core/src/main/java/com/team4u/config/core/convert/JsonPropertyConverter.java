package com.team4u.config.core.convert;

import cn.hutool.json.JSONUtil;

/**
 * 基于 Hutool JSON 的通用属性转换器
 * <p>
 * 该转换器可以直接在 @ConfigConverter 中使用，无需为每个类型创建子类。
 *
 * @author jay.wu
 */
public class JsonPropertyConverter implements PropertyConverter<Object> {

    @Override
    public Object convert(String source, Class<Object> targetType) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        return JSONUtil.toBean(source, targetType);
    }
}