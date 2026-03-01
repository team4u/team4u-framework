package com.team4u.log.mask.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;
import com.fasterxml.jackson.databind.ser.std.MapSerializer;
import com.team4u.log.mask.FastMasker;
import com.team4u.log.mask.MaskType;
import com.team4u.log.mask.config.MaskRuleRepository;

import java.io.IOException;
import java.util.Map;

/**
 * 脱敏 Map 序列化器
 * <p>
 * 包装 Jackson 原生的 MapSerializer，在写入键值对时根据规则执行脱敏。
 */
public class MaskableMapSerializer extends JsonSerializer<Map<?, ?>> implements ContextualSerializer {

    private final MapSerializer delegate;
    private final String mapClassName;

    public MaskableMapSerializer(MapSerializer delegate, String mapClassName) {
        this.delegate = delegate;
        this.mapClassName = mapClassName;
    }

    @Override
    public void serialize(Map<?, ?> value, JsonGenerator gen, SerializerProvider provider) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }

        gen.writeStartObject();

        for (Map.Entry<?, ?> entry : value.entrySet()) {
            Object key = entry.getKey();
            Object val = entry.getValue();

            gen.writeFieldName(String.valueOf(key));

            // 如果值为字符串，尝试匹配动态规则执行脱敏
            if (val instanceof String && key instanceof String) {
                String strKey = (String) key;
                MaskType maskType = MaskRuleRepository.getInstance().findRule(mapClassName, strKey);

                if (maskType != null) {
                    gen.writeString(FastMasker.mask((String) val, maskType));
                } else {
                    gen.writeString((String) val);
                }
            } else {
                // 递归处理复杂对象
                provider.defaultSerializeValue(val, gen);
            }
        }

        gen.writeEndObject();
    }

    @Override
    public JsonSerializer<?> createContextual(SerializerProvider prov,
            com.fasterxml.jackson.databind.BeanProperty property) {
        // 核心逻辑在 serialize 中，此处保持当前实例
        return this;
    }
}
