package com.team4u.log.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.team4u.log.appender.LogAppender;
import com.team4u.log.appender.Slf4jLogAppender;
import com.team4u.log.mask.config.MaskRuleRepository;
import com.team4u.log.mask.jackson.ByteArrayLogSerializer;
import com.team4u.log.mask.jackson.DynamicMaskSerializerModifier;
import com.team4u.log.mask.jackson.TruncatingStringSerializer;
import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;

/**
 * 日志核心引擎
 * <p>
 * 负责管理日志处理流程，调度拦截器链，并执行日志的脱敏与序列化。
 */
public class LogEngine {

    private static final LogEngine INSTANCE = new LogEngine();

    /**
     * 拦截器管理器
     */
    private final LogInterceptorManager interceptorManager;

    /**
     * ObjectMapper 非 final，允许在重置时清空序列化器缓存
     */
    private volatile ObjectMapper objectMapper;

    /**
     * 全局日志序列化最大长度阈值
     */
    @Setter
    @Getter
    private volatile int maxLogLength = 5000;

    /**
     * 单个字符串字段的最大长度（防止单个大报文/文件撑爆内存）
     */
    @Setter
    @Getter
    private volatile int maxStringLength = 2000;

    /**
     * 日志追加器适配器
     */
    @Setter
    @Getter
    private LogAppender appender = new Slf4jLogAppender();

    private LogEngine() {
        // 1. 初始化拦截器管理器
        this.interceptorManager = new LogInterceptorManager();

        // 2. 初始化 Jackson 序列化器
        this.objectMapper = createObjectMapper();
    }

    /**
     * 获取日志引擎单例实例
     *
     * @return LogEngine 实例
     */
    public static LogEngine getInstance() {
        return INSTANCE;
    }

    /**
     * 获取拦截器管理器
     *
     * @return 拦截器管理器实例
     */
    public LogInterceptorManager getInterceptorManager() {
        return interceptorManager;
    }

    private ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        SimpleModule module = new SimpleModule();

        // 1. 注册全局字符串截断器 (防大文本)
        module.addSerializer(String.class, new TruncatingStringSerializer());

        // 2. 注册字节数组拦截器 (防大文件 Base64 内存溢出)
        module.addSerializer(byte[].class, new ByteArrayLogSerializer());

        // 3. 注册动态脱敏修饰器 (对接规则库)
        module.setSerializerModifier(new DynamicMaskSerializerModifier());

        mapper.registerModule(module);
        return mapper;
    }

    /**
     * 重置引擎配置及拦截器状态
     */
    public void reset() {
        this.maxLogLength = 5000;
        this.maxStringLength = 2000;
        this.appender = new Slf4jLogAppender();
        this.interceptorManager.reset();
        MaskRuleRepository.getInstance().refreshRules(new HashMap<>());
        // 重置 ObjectMapper 以清空序列化器缓存
        this.objectMapper = createObjectMapper();
    }

    /**
     * 处理并输出日志事件
     *
     * @param event 日志事件
     */
    public void processAndOutput(LogEvent event) {
        // 1. 执行动态染色、MDC 注入及限流逻辑
        boolean passed = interceptorManager.execute(event);

        // 如果被拦截器抑制或处理链中断，则终止处理
        if (!passed || event.isSuppressed()) {
            return;
        }

        // 2. 写入输出层
        if (appender != null) {
            appender.append(event);
        }
    }

    /**
     * 将日志事件序列化为 JSON 字符串
     *
     * @param event 日志事件
     * @return JSON 字符串
     */
    public String toJson(LogEvent event) {
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
}
