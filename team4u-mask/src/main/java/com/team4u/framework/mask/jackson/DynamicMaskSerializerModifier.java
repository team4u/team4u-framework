package com.team4u.framework.mask.jackson;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.ser.std.MapSerializer;
import com.fasterxml.jackson.databind.type.MapType;
import com.team4u.framework.mask.Mask;
import com.team4u.framework.mask.config.MaskRuleRepository;

import java.util.List;

/**
 * 动态脱敏序列化修饰器
 * <p>
 * 支持根据外部规则或注解对第三方类及动态 Map 进行脱敏。
 */
public class DynamicMaskSerializerModifier extends BeanSerializerModifier {

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
                writer.assignSerializer(new MaskStringSerializer(maskAnnotation.value().name()));
                continue;
            }

            // 2. 使用外部规则库配置
            // 规则仅在构建序列化器时执行，提升性能
            String externalRule = MaskRuleRepository.getInstance().findRule(className, fieldName);
            if (externalRule != null) {
                writer.assignSerializer(new MaskStringSerializer(externalRule));
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
            return new MaskableMapSerializer((MapSerializer) serializer,
                    beanDesc.getBeanClass().getName());
        }
        return serializer;
    }
}
