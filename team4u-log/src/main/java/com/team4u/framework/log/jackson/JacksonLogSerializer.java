package com.team4u.framework.log.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.team4u.framework.log.config.FinOpsConfigRepository;
import com.team4u.framework.log.config.FinOpsConfigRepository.FinOpsConfig;
import com.team4u.framework.log.core.LogEvent;
import com.team4u.framework.log.core.LogSerializer;
import com.team4u.framework.mask.jackson.JacksonMaskModule;
import com.team4u.framework.mask.jackson.MaskConfig;
import com.team4u.framework.serializer.json.jackson.JacksonSerializerPolicy;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基于 Jackson 的日志序列化器
 * <p>
 * <b>Mapper 构建策略</b>：以 {@link JacksonSerializerPolicy#sharedMapper()} 的副本为基底，
 * 叠加日志特有的截断模块（{@link TruncatingStringSerializer} + {@link ByteArrayLogSerializer}）。
 * <p>
 * 截断模块<b>不注册进全局共享 mapper</b>，原因：
 * {@link TruncatingStringSerializer} 对 {@code String.class} 全量生效（非按注解/类型过滤），
 * 且无上下文属性时回退到 FinOps 默认 2000 字符。若注册全局，所有 {@code JsonUtil} 消费方
 * （retry 载荷、router 策略、lease 编解码等）的普通字符串都会被无差别截断，污染全局语义。
 * 脱敏模块（{@link JacksonMaskModule}）则相反，它按注解/规则精确匹配字段，
 * 已通过 mask 模块的 {@code JacksonModuleContributor} SPI 注册进共享 mapper，
 * 副本继承该能力，无需本类重复注册。
 */
public class JacksonLogSerializer implements LogSerializer {

    private static final ObjectMapper FALLBACK_MAPPER = new ObjectMapper();
    private volatile ObjectMapper objectMapper;

    public JacksonLogSerializer() {
        this.objectMapper = createObjectMapper();
    }

    private ObjectMapper createObjectMapper() {
        // 以全局共享 mapper 的副本为基底：继承 SPI 注册的脱敏等模块与基础配置，
        // 同时隔离本类追加的截断模块，避免污染其他 JsonUtil 消费方
        ObjectMapper mapper = JacksonSerializerPolicy.sharedMapper().copy();

        // 注册日志特有的防超长截断拦截器
        SimpleModule logModule = new SimpleModule();
        logModule.addSerializer(String.class, new TruncatingStringSerializer());
        logModule.addSerializer(byte[].class, new ByteArrayLogSerializer());
        mapper.registerModule(logModule);

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
        // 重置 ObjectMapper：重新从共享 mapper 派生副本，清空序列化器缓存并
        // 感知共享 mapper 侧的模块集变化（如晚注册的脱敏模块）
        this.objectMapper = createObjectMapper();
    }
}
