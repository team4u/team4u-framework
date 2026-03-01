package com.team4u.log;

import com.team4u.log.pipeline.context.LogContextCollector;
import com.team4u.log.pipeline.context.LogContextContributor;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局日志上下文工具类
 * <p>
 * 提供静态方法用于设置全局属性、当前线程局部属性或注册自定义上下文贡献者。
 */
public class LogContext {

    private static final LogContextCollector COLLECTOR = new LogContextCollector();

    /**
     * 线程局部上下文存储
     */
    private static final ThreadLocal<Map<String, Object>> THREAD_CONTEXT = ThreadLocal.withInitial(HashMap::new);

    /**
     * 设置全局静态上下文属性（应用级，如 env, appName 等）
     *
     * @param key   属性键
     * @param value 属性值
     */
    public static void setGlobal(String key, Object value) {
        COLLECTOR.setGlobal(key, value);
    }

    /**
     * 批量设置全局静态上下文属性
     *
     * @param attributes 属性 Map
     */
    public static void setGlobals(Map<String, Object> attributes) {
        COLLECTOR.setGlobals(attributes);
    }

    /**
     * 设置当前线程的上下文属性（请求级）
     * 🚨 注意：必须在请求结束时调用 {@link #clearCurrent()} 释放内存。
     *
     * @param key   属性键
     * @param value 属性值
     */
    public static void setCurrent(String key, Object value) {
        THREAD_CONTEXT.get().put(key, value);
    }

    /**
     * 获取当前线程的所有上下文属性
     *
     * @return 属性 Map
     */
    public static Map<String, Object> getCurrentAttributes() {
        return THREAD_CONTEXT.get();
    }

    /**
     * 清理当前线程的上下文属性，防止内存泄漏
     */
    public static void clearCurrent() {
        THREAD_CONTEXT.remove();
    }

    /**
     * 注册新的上下文贡献者
     *
     * @param contributor 贡献者实现
     */
    public static void addContributor(LogContextContributor contributor) {
        COLLECTOR.addContributor(contributor);
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
     * 重置全局配置（不影响当前线程）
     */
    public static void reset() {
        COLLECTOR.reset();
    }
}
