package com.team4u.log.mask.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.team4u.log.config.LogConfigManager;
import com.team4u.log.mask.FastMasker;

import java.io.IOException;

/**
 * 脱敏专用字符串序列化器
 */
public class MaskStringSerializer extends StdSerializer<Object> {
    private final String maskType;

    public MaskStringSerializer(JacksonLogSerializer serializer, String maskType) {
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

        // 应用长度截断
        int maxLength = LogConfigManager.getInstance().getCurrentConfig()
                .getFinOpsConfig().getMaxStringLength();

        if (maxLength > 0 && masked.length() > maxLength) {
            gen.writeString(masked.substring(0, maxLength) + "... [Truncated len:" + masked.length() + "]");
        } else {
            gen.writeString(masked);
        }
    }
}
