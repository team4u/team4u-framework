package com.team4u.log.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.team4u.framework.policy.core.OrderedPolicyChain;
import com.team4u.framework.policy.engine.PolicyPipeline;
import com.team4u.log.appender.LogAppender;
import com.team4u.log.appender.Slf4jLogAppender;
import com.team4u.log.mask.config.MaskRuleRepository;
import com.team4u.log.mask.jackson.DynamicMaskSerializerModifier;
import com.team4u.log.pipeline.LogInterceptor;
import com.team4u.log.pipeline.interceptor.MdcEnrichInterceptor;
import com.team4u.log.pipeline.interceptor.RateLimitInterceptor;
import com.team4u.log.pipeline.interceptor.TargetedDyeingInterceptor;

/**
 * 日志核心引擎
 * <p>
 * 负责管理日志处理流程，调度拦截器链，并执行日志的脱敏与序列化。
 */
public class LogEngine {

    private static final LogEngine INSTANCE = new LogEngine();

    private final PolicyPipeline<LogEvent, LogInterceptor> pipeline;
    /**
     * ObjectMapper 非 final，允许在重置时清空序列化器缓存
     */
    private ObjectMapper objectMapper;

    /**
     * 全局日志序列化最大长度阈值
     */
    private volatile int maxLogLength = 5000;

    /**
     * 日志追加器适配器
     */
    private LogAppender appender = new Slf4jLogAppender();

    private LogEngine() {
        // 1. 初始化拦截器链
        // 显式注册单例拦截器，确保与 LogConfigManager 的动态变更同步
        OrderedPolicyChain<LogEvent, LogInterceptor> chain = new OrderedPolicyChain<>(LogInterceptor.class);
        chain.register(MdcEnrichInterceptor.getInstance());
        chain.register(TargetedDyeingInterceptor.getInstance());
        chain.register(RateLimitInterceptor.getInstance());

        this.pipeline = new PolicyPipeline<>(chain);

        // 2. 初始化 Jackson 序列化器
        this.objectMapper = createObjectMapper();
    }

    public static LogEngine getInstance() {
        return INSTANCE;
    }

    private ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        SimpleModule maskModule = new SimpleModule();
        // 注册动态脱敏修饰器，对接 MaskRuleRepository
        maskModule.setSerializerModifier(new DynamicMaskSerializerModifier());
        mapper.registerModule(maskModule);
        return mapper;
    }

    public LogAppender getAppender() {
        return appender;
    }

    public void setAppender(LogAppender appender) {
        this.appender = appender;
    }

    public int getMaxLogLength() {
        return maxLogLength;
    }

    public void setMaxLogLength(int maxLogLength) {
        this.maxLogLength = maxLogLength;
    }

    /**
     * 重置引擎配置及拦截器状态
     */
    public void reset() {
        this.maxLogLength = 5000;
        this.appender = new Slf4jLogAppender();
        MdcEnrichInterceptor.getInstance().reset();
        TargetedDyeingInterceptor.getInstance().reset();
        RateLimitInterceptor.getInstance().reset();
        MaskRuleRepository.getInstance().reset();
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
        boolean passed = pipeline.executeChain(event, (interceptor, evt) -> interceptor.handle(evt));

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
            return "{\"error\": \"Serialization failed\", \"reason\": \"" + e.getMessage() + "\"}";
        }
    }
}
