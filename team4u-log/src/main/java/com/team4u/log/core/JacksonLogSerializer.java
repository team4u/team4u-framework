package com.team4u.log.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.team4u.log.mask.jackson.ByteArrayLogSerializer;
import com.team4u.log.mask.jackson.DynamicMaskSerializerModifier;
import com.team4u.log.mask.jackson.TruncatingStringSerializer;
import lombok.Getter;
import lombok.Setter;

/**
 * 基于 Jackson 的日志序列化器
 */
public class JacksonLogSerializer implements LogSerializer {

    private volatile ObjectMapper objectMapper;

    @Setter
    @Getter
    private volatile int maxLogLength = 5000;

    @Setter
    @Getter
    private volatile int maxStringLength = 2000;

    public JacksonLogSerializer() {
        this.objectMapper = createObjectMapper();
    }

    private ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        SimpleModule module = new SimpleModule();

        // 1. 注册全局字符串截断器 (防大文本)
        module.addSerializer(String.class, new TruncatingStringSerializer(this));

        // 2. 注册字节数组拦截器 (防大文件 Base64 内存溢出)
        module.addSerializer(byte[].class, new ByteArrayLogSerializer());

        // 3. 注册动态脱敏修饰器 (对接规则库)
        module.setSerializerModifier(new DynamicMaskSerializerModifier(this));

        mapper.registerModule(module);
        return mapper;
    }

    @Override
    public String serialize(LogEvent event) {
        try {
            // 执行脱敏序列化
            String rawJson = objectMapper.writeValueAsString(event);

            // 根据配置的体积阈值截断超长日志
            if (rawJson.length() > maxLogLength) {
                return rawJson.substring(0, maxLogLength) + "... [Truncated at " + maxLogLength + "]";
            }
            return rawJson;
        } catch (Exception e) {
            return String.format("{\"error\": \"Serialization failed\", \"action\": \"%s\", \"reason\": \"%s\"}",
                    event.getAction() != null ? event.getAction() : "",
                    e.getMessage());
        }
    }

    @Override
    public void reset() {
        this.maxLogLength = 5000;
        this.maxStringLength = 2000;
        // 重置 ObjectMapper 以清空序列化器缓存
        this.objectMapper = createObjectMapper();
    }
}
