package com.team4u.framework.mask.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.team4u.framework.mask.FastMasker;
import com.team4u.framework.mask.MaskUtils;

import java.io.IOException;

/**
 * 脱敏专用字符串序列化器
 */
public class MaskStringSerializer extends StdSerializer<Object> {
    private final String maskType;

    public MaskStringSerializer(String maskType) {
        super(Object.class);
        this.maskType = maskType;
    }

    @Override
    public void serialize(Object value, JsonGenerator gen, SerializerProvider provider) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }

        // 直接由 FastMasker 处理，无反射、无正则
        String masked = FastMasker.mask(value.toString(), maskType);
        if (masked == null) {
            gen.writeNull();
            return;
        }

        // 应用长度截断 (从序列化上下文中获取最大长度)
        MaskConfig config = JacksonSerializationContext.getConfig(provider);
        int maxLength = config.getMaxStringLength();

        if (maxLength > 0 && MaskUtils.codePointLength(masked) > maxLength) {
            gen.writeString(MaskUtils.limit(masked, maxLength) + "... [Truncated len:" + MaskUtils.codePointLength(masked) + "]");
        } else {
            gen.writeString(masked);
        }
    }
}
