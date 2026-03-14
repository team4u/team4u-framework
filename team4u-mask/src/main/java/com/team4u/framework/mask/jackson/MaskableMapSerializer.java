package com.team4u.framework.mask.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;
import com.fasterxml.jackson.databind.ser.std.MapSerializer;
import com.team4u.framework.mask.FastMasker;
import com.team4u.framework.mask.MaskUtils;
import com.team4u.framework.mask.config.MaskRuleRepository;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 脱敏 Map 序列化器
 * <p>
 * 在保持 Jackson 原生 MapSerializer 行为的前提下，对匹配规则的字符串值执行脱敏。
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
        delegate.serialize(transform(value, provider), gen, provider);
    }

    @Override
    public void serializeWithType(Map<?, ?> value,
                                  JsonGenerator gen,
                                  SerializerProvider provider,
                                  TypeSerializer typeSer) throws IOException {
        delegate.serializeWithType(transform(value, provider), gen, provider, typeSer);
    }

    @Override
    public JsonSerializer<?> createContextual(SerializerProvider prov, BeanProperty property) throws JsonMappingException {
        JsonSerializer<?> contextual = delegate;
        if (delegate != null) {
            contextual = delegate.createContextual(prov, property);
        }

        if (contextual instanceof MapSerializer) {
            return new MaskableMapSerializer((MapSerializer) contextual, mapClassName);
        }
        return contextual;
    }

    /**
     * 将包含需脱敏内容的Map转换为已脱敏的新Map
     * <p>
     * 遍历原始Map，针对键为字符串且值为字符串的条目，结合动态规则执行脱敏处理。
     * 为提升性能，仅在数据真正发生变更时，才创建和返回新的Map结构。
     *
     * @param value    原始Map对象
     * @param provider 序列化上下文提供者，用于获取脱敏配置，如最大限制长度
     * @return 经过脱敏和长度截断处理后的Map，若无需改变则返回原引用
     */
    private Map<?, ?> transform(Map<?, ?> value, SerializerProvider provider) {
        if (value == null || value.isEmpty()) {
            return value;
        }

        MaskConfig config = JacksonSerializationContext.getConfig(provider);
        Map<Object, Object> transformed = new LinkedHashMap<>(value.size());
        boolean changed = false;

        for (Map.Entry<?, ?> entry : value.entrySet()) {
            Object key = entry.getKey();
            Object originalValue = entry.getValue();
            Object transformedValue = originalValue;

            if (key instanceof String && originalValue instanceof String) {
                String maskType = MaskRuleRepository.getInstance().findRule(mapClassName, (String) key);
                String maskedValue = maskType != null
                        ? FastMasker.mask((String) originalValue, maskType)
                        : (String) originalValue;
                transformedValue = applyLengthLimit(maskedValue, config.getMaxStringLength());
            }

            if (!changed && transformedValue != originalValue) {
                changed = true;
            }
            transformed.put(key, transformedValue);
        }

        return changed ? transformed : value;
    }

    /**
     * 执行安全的字符串长度限制处理
     * <p>
     * 在处理含有Emoji或特殊符号（多字节字符）的字符串时，
     * 基于 Unicode Code Point 进行截断，以避免出现乱码或被截半的不可见字符。
     *
     * @param value     被处理的字符串
     * @param maxLength 允许展示的最大长度，如果不受限则配置为 -1 或 0
     * @return 截取并带有提示标识的部分内容视图，超过要求直接追加截断说明；未超过限制直接返回原始值
     */
    private String applyLengthLimit(String value, int maxLength) {
        if (value == null || maxLength <= 0 || MaskUtils.codePointLength(value) <= maxLength) {
            return value;
        }
        return MaskUtils.limit(value, maxLength) + "... [Truncated len:" + MaskUtils.codePointLength(value) + "]";
    }
}
