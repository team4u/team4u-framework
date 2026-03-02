package com.team4u.log.mask.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.ser.std.MapSerializer;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.databind.type.MapType;
import com.team4u.log.core.LogSerializer;
import com.team4u.log.mask.FastMasker;
import com.team4u.log.mask.Mask;
import com.team4u.log.mask.config.MaskRuleRepository;

import java.io.IOException;
import java.util.List;

/**
 * 动态脱敏序列化修饰器
 * <p>
 * 支持根据外部规则或注解对第三方类及动态 Map 进行脱敏。
 */
public class DynamicMaskSerializerModifier extends BeanSerializerModifier {

    private final LogSerializer serializer;

    public DynamicMaskSerializerModifier(LogSerializer serializer) {
        this.serializer = serializer;
    }

    @Override
    public List<BeanPropertyWriter> changeProperties(SerializationConfig config,
                                                     BeanDescription beanDesc,
                                                     List<BeanPropertyWriter> beanProperties) {
        String className = beanDesc.getBeanClass().getName();

        for (BeanPropertyWriter writer : beanProperties) {
            String fieldName = writer.getName();

            // 1. 优先使用注解配置
            Mask maskAnnotation = writer.getAnnotation(Mask.class);
            if (maskAnnotation != null) {
                writer.assignSerializer(new MaskStringSerializer(serializer, maskAnnotation.value().name()));
                continue;
            }

            // 2. 使用外部规则库配置
            // 规则仅在构建序列化器时执行，提升性能
            String externalRule = MaskRuleRepository.getInstance().findRule(className, fieldName);
            if (externalRule != null) {
                writer.assignSerializer(new MaskStringSerializer(serializer, externalRule));
            }
        }
        return beanProperties;
    }

    @Override
    public JsonSerializer<?> modifyMapSerializer(SerializationConfig config,
                                                 MapType valueType,
                                                 BeanDescription beanDesc,
                                                 JsonSerializer<?> serializer) {
        // 针对 Map 类型的特殊处理，实现无侵入脱敏
        if (serializer instanceof MapSerializer) {
            return new MaskableMapSerializer(this.serializer, (MapSerializer) serializer, beanDesc.getBeanClass().getName());
        }
        return serializer;
    }

    /**
     * 脱敏专用字符串序列化器
     */
    private static class MaskStringSerializer extends StdSerializer<Object> {
        private final LogSerializer serializer;
        private final String maskType;

        public MaskStringSerializer(LogSerializer serializer, String maskType) {
            super(Object.class);
            this.serializer = serializer;
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
            int maxLength = serializer.getMaxStringLength();
            if (maxLength > 0 && masked.length() > maxLength) {
                gen.writeString(masked.substring(0, maxLength) + "... [Truncated len:" + masked.length() + "]");
            } else {
                gen.writeString(masked);
            }
        }
    }
}
