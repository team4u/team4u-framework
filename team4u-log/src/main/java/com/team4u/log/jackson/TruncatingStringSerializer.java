package com.team4u.log.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.team4u.mask.jackson.JacksonSerializationContext;

import java.io.IOException;

/**
 * 全局字符串截断序列化器
 * <p>
 * 在 JSON 序列化阶段直接截断超长字符串，避免大报文消耗过多 CPU 和内存。
 */
public class TruncatingStringSerializer extends StdSerializer<String> {

    public TruncatingStringSerializer(JacksonLogSerializer serializer) {
        super(String.class);
    }

    @Override
    public void serialize(String value, JsonGenerator gen, SerializerProvider provider) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }

        // 优先从上下文解析，如果没有上下文，回退到日志模块的全局配置
        int maxLength;
        Object attribute = provider.getAttribute(JacksonSerializationContext.ATTR_FINOPS_CONFIG_SNAPSHOT);
        if (attribute != null) {
            maxLength = JacksonSerializationContext.resolveMaxStringLength(provider);
        } else {
            maxLength = com.team4u.log.config.FinOpsConfigRepository.getInstance().get().getMaxStringLength();
        }

        if (maxLength > 0 && value.length() > maxLength) {
            // 直接在写入前截断，避免 Jackson 分配巨大的缓冲区
            gen.writeString(value.substring(0, maxLength) + "... [Truncated len:" + value.length() + "]");
        } else {
            gen.writeString(value);
        }
    }
}
