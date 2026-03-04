package com.team4u.log.core;

import com.team4u.log.appender.LogAppender;
import com.team4u.log.appender.Slf4jLogAppender;
import com.team4u.log.config.FinOpsConfigRepository;
import com.team4u.log.jackson.JacksonLogSerializer;
import com.team4u.log.pipeline.LogInterceptorManager;
import com.team4u.log.proxy.ProxyRuleRepository;
import com.team4u.framework.mask.MaskBootstrap;
import lombok.Getter;
import lombok.Setter;

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
    @Getter
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
     * 重置引擎及所有子组件状态，用于测试隔离
     * <p>
     * 统一复位：追加器、拦截器链、各配置仓库及序列化器。
     * 调用后整个日志系统恢复到初始空白状态，下次可重新 init。
     */
    public void reset() {
        // 追加器恢复默认
        this.appender = new Slf4jLogAppender();
        // 拦截器链清空
        this.interceptorManager.reset();
        // 各配置驱动仓库归零，释放旧 ConfigManager 的监听
        MaskBootstrap.global().stop();
        ProxyRuleRepository.getInstance().reset();
        FinOpsConfigRepository.getInstance().reset();
        // 重建 ObjectMapper，清空 Jackson 的 BeanSerializer 缓存
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