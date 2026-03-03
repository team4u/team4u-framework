package com.team4u.log;

import com.team4u.log.pipeline.context.LogContextCollector;
import com.team4u.log.pipeline.context.LogContextSource;

/**
 * 全局日志上下文工具类
 * <p>
 * 提供静态方法用于注册自定义上下文寻值源。
 * 核心逻辑由 {@link LogContextCollector} 负责，已重构为高性能的 Pull（拉）模型。
 *
 * @author team4u
 */
public class LogContext {

    private static final LogContextCollector COLLECTOR = new LogContextCollector();

    /**
     * 注册新的上下文寻值源 (Pull 模型)
     *
     * @param source 寻值源实现
     */
    public static void addSource(LogContextSource source) {
        COLLECTOR.addSource(source);
    }

    /**
     * 获取内部收集器实例
     *
     * @return 收集器实例
     */
    public static LogContextCollector getCollector() {
        return COLLECTOR;
    }

    /**
     * 重置全局配置
     */
    public static void reset() {
        COLLECTOR.reset();
    }
}
