package com.team4u.framework.log.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.team4u.framework.log.config.FinOpsConfigRepository;
import com.team4u.framework.mask.jackson.JacksonSerializationContext;
import com.team4u.framework.mask.jackson.MaskConfig;

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

        // 1. 核心改进：通过 Mask 模块的上下文工具获取 MaskConfig 对象
        // 这样即便字段不脱敏，也能拿到 LogEngine 序列化时注入的特定阈值
        MaskConfig maskConfig = JacksonSerializationContext.getConfig(provider);
        int maxLength = maskConfig.getMaxStringLength();

        // 2. 兜底逻辑：如果上下文中没有配置（比如单独调用了 ObjectMapper），则回退到 Log 模块的全局配置
        if (maxLength <= 0) {
            maxLength = FinOpsConfigRepository.getInstance().get().getMaxStringLength();
        }

        if (maxLength > 0 && value.length() > maxLength) {
            // 直接在写入前截断，避免 Jackson 分配巨大的缓冲区
            gen.writeString(value.substring(0, maxLength) + "... [Truncated len:" + value.length() + "]");
        } else {
            gen.writeString(value);
        }
    }
}
