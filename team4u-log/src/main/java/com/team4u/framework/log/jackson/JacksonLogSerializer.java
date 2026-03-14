package com.team4u.framework.log.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.team4u.framework.log.config.FinOpsConfigRepository;
import com.team4u.framework.log.config.FinOpsConfigRepository.FinOpsConfig;
import com.team4u.framework.log.core.LogEvent;
import com.team4u.framework.log.core.LogSerializer;
import com.team4u.framework.mask.jackson.JacksonMaskModule;
import com.team4u.framework.mask.jackson.MaskConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基于 Jackson 的日志序列化器
 */
public class JacksonLogSerializer implements LogSerializer {

    private static final ObjectMapper FALLBACK_MAPPER = new ObjectMapper();
    private volatile ObjectMapper objectMapper;

    public JacksonLogSerializer() {
        this.objectMapper = createObjectMapper();
    }

    private ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

        // 1. 注册日志特有的防超长截断拦截器
        SimpleModule logModule = new SimpleModule();
        logModule.addSerializer(String.class, new TruncatingStringSerializer());
        logModule.addSerializer(byte[].class, new ByteArrayLogSerializer());
        mapper.registerModule(logModule);

        // 2. 注册通用的脱敏模块
        mapper.registerModule(new JacksonMaskModule());

        return mapper;
    }

    @Override
    public String serialize(LogEvent event) {
        try {
            FinOpsConfig finOpsConfig = FinOpsConfigRepository.getInstance().get();

            // 领域映射：把 Log 层的成本阈值，映射为 Mask 层的上下文限制
            MaskConfig maskConfig = new MaskConfig()
                    .setMaxStringLength(finOpsConfig.getMaxStringLength());

            // 执行序列化，精准下发配置
            String rawJson = objectMapper.writer()
                    .withAttribute(MaskConfig.ATTR_KEY, maskConfig)
                    .writeValueAsString(event);

            // 获取最大日志长度限制
            int maxLogLength = finOpsConfig.getMaxLogLength();

            // 根据配置的体积阈值截断超长日志
            if (rawJson.length() > maxLogLength) {
                return rawJson.substring(0, maxLogLength) + "... [Truncated at " + maxLogLength + "]";
            }
            return rawJson;
        } catch (Exception e) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("error", "Serialization failed");
            fallback.put("action", event.getAction() != null ? event.getAction() : "");
            fallback.put("reason", e.getMessage() != null ? e.getMessage() : e.getClass().getName());
            try {
                return FALLBACK_MAPPER.writeValueAsString(fallback);
            } catch (Exception ignored) {
                return "{\"error\":\"Serialization failed\"}";
            }
        }
    }

    @Override
    public void reset() {
        // 重置 ObjectMapper 以清空序列化器缓存
        this.objectMapper = createObjectMapper();
    }
}
