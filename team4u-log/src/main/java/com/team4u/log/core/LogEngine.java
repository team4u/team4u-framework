package com.team4u.log.core;

import com.team4u.log.appender.LogAppender;
import com.team4u.log.appender.Slf4jLogAppender;
import com.team4u.log.mask.config.MaskRuleRepository;
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
     * 日志序列化器
     */
    private final LogSerializer serializer;

    /**
     * 日志追加器适配器
     */
    @Setter
    @Getter
    private LogAppender appender = new Slf4jLogAppender();

    private LogEngine() {
        // 1. 初始化拦截器管理器
        this.interceptorManager = new LogInterceptorManager();

        // 2. 初始化序列化器
        this.serializer = new JacksonLogSerializer();
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

    /**
     * 获取全局日志序列化最大长度阈值
     *
     * @return 最大长度
     */
    public int getMaxLogLength() {
        return serializer.getMaxLogLength();
    }

    /**
     * 设置全局日志序列化最大长度阈值
     *
     * @param maxLogLength 最大长度
     */
    public void setMaxLogLength(int maxLogLength) {
        serializer.setMaxLogLength(maxLogLength);
    }

    /**
     * 获取单个字符串字段的最大长度
     *
     * @return 最大长度
     */
    public int getMaxStringLength() {
        return serializer.getMaxStringLength();
    }

    /**
     * 设置单个字符串字段的最大长度
     *
     * @param maxStringLength 最大长度
     */
    public void setMaxStringLength(int maxStringLength) {
        serializer.setMaxStringLength(maxStringLength);
    }

    /**
     * 重置引擎配置及拦截器状态
     */
    public void reset() {
        this.appender = new Slf4jLogAppender();
        this.interceptorManager.reset();
        MaskRuleRepository.getInstance().refreshRules(new HashMap<>());
        // 重置序列化器
        this.serializer.reset();
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
        return serializer.serialize(event);
    }
}
