package com.team4u.framework.config.core.convert;

import com.team4u.framework.serializer.json.JsonUtil;

/**
 * 基于 JSON 的通用属性转换器
 * <p>
 * 内部利用 JsonUtil 工具类将配置字符串反序列化为目标 Java 对象。
 * 该转换器可直接配合 {@link com.team4u.framework.config.core.annotation.ConfigConverter} 注解使用。
 * </p>
 *
 * @author jay.wu
 */
public class JsonPropertyConverter implements PropertyConverter<Object> {

    @Override
    public Object convert(String source, Class<Object> targetType) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        return JsonUtil.toBean(source, targetType);
    }
}