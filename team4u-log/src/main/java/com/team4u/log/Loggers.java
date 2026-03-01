package com.team4u.log;

import com.team4u.log.core.LogEngine;
import com.team4u.log.core.LogEvent;
import com.team4u.log.pipeline.interceptor.TargetedDyeingInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.util.Map;

/**
 * 结构化日志 Fluent API
 * <p>
 * 提供链式调用的日志构建接口，延迟到调用 log() 时才进行最终处理。
 */
public class Loggers {

    private final LogEvent event;
    private final Logger slf4jLogger;

    private Loggers(Class<?> clazz) {
        this.slf4jLogger = LoggerFactory.getLogger(clazz);
        this.event = new LogEvent().setLoggerName(clazz.getName());
    }

    private Loggers(Logger slf4jLogger, LogEvent event) {
        this.slf4jLogger = slf4jLogger;
        this.event = event;
    }

    /**
     * 为指定类创建日志记录器
     *
     * @param clazz 类对象
     * @return Loggers 实例
     */
    public static Loggers of(Class<?> clazz) {
        return new Loggers(clazz);
    }

    /**
     * 派生当前日志器状态，生成一个新的独立实例。
     * <p>
     * 常用于将当前 Loggers 作为模板（Template），
     * 预置公共的 KV 属性或动作，在具体使用时 fork 出来，避免状态污染。
     *
     * @return 派生的 Loggers 实例
     */
    public Loggers fork() {
        return new Loggers(this.slf4jLogger, this.event.fork());
    }

    public LogEvent getEvent() {
        return event;
    }

    /**
     * 设置动作名称
     *
     * @param action 动作
     * @return 当前实例
     */
    public Loggers action(String action) {
        this.event.setAction(action);
        return this;
    }

    /**
     * 设置状态
     *
     * @param status 状态字符串
     * @return 当前实例
     */
    public Loggers status(String status) {
        this.event.setStatus(status);
        return this;
    }

    /**
     * 标记为成功状态，默认级别为 INFO
     *
     * @return 当前实例
     */
    public Loggers success() {
        this.event.setStatus("success");
        if (this.event.getLevel() == null) {
            this.event.setLevel(Level.INFO);
        }
        return this;
    }

    /**
     * 标记为失败状态并绑定异常，默认级别为 ERROR
     *
     * @param e 异常对象
     * @return 当前实例
     */
    public Loggers failed(Throwable e) {
        this.event.setStatus("failed");
        this.event.setException(e);
        if (this.event.getLevel() == null) {
            this.event.setLevel(Level.ERROR);
        }
        return this;
    }

    /**
     * 设置日志级别
     *
     * @param level 日志级别
     * @return 当前实例
     */
    public Loggers level(Level level) {
        this.event.setLevel(level);
        return this;
    }

    /**
     * 设置日志级别为 TRACE
     *
     * @return 当前实例
     */
    public Loggers atTrace() {
        return level(Level.TRACE);
    }

    /**
     * 设置日志级别为 DEBUG
     *
     * @return 当前实例
     */
    public Loggers atDebug() {
        return level(Level.DEBUG);
    }

    /**
     * 设置日志级别为 INFO
     *
     * @return 当前实例
     */
    public Loggers atInfo() {
        return level(Level.INFO);
    }

    /**
     * 设置日志级别为 WARN
     *
     * @return 当前实例
     */
    public Loggers atWarn() {
        return level(Level.WARN);
    }

    /**
     * 设置日志级别为 ERROR
     *
     * @return 当前实例
     */
    public Loggers atError() {
        return level(Level.ERROR);
    }

    /**
     * 设置耗时（毫秒）
     *
     * @param ms 毫秒
     * @return 当前实例
     */
    public Loggers duration(long ms) {
        this.event.setDurationMs(ms);
        return this;
    }

    /**
     * 添加业务数据 K-V 对
     *
     * @param key   键
     * @param value 值
     * @return 当前实例
     */
    public Loggers kv(String key, Object value) {
        this.event.getPayload().put(key, value);
        return this;
    }

    /**
     * 批量添加业务数据 K-V 对
     *
     * @param map K-V 集合
     * @return 当前实例
     */
    public Loggers kvs(Map<String, Object> map) {
        if (map != null) {
            this.event.getPayload().putAll(map);
        }
        return this;
    }

    /**
     * 提交日志事件
     */
    public void log() {
        // 性能保护：若无有效染色规则且日志级别未达到输出标准，则忽略该日志
        if (!TargetedDyeingInterceptor.getInstance().hasActiveRules()
                && event.getLevel() != null && !isLevelEnabled(event.getLevel())) {
            return;
        }

        // 提交核心引擎处理
        LogEngine.getInstance().processAndOutput(this.event);
    }

    /**
     * 检查当前记录器是否启用了指定级别
     *
     * @param level 日志级别
     * @return 是否启用
     */
    private boolean isLevelEnabled(Level level) {
        switch (level) {
            case INFO:
                return slf4jLogger.isInfoEnabled();
            case DEBUG:
                return slf4jLogger.isDebugEnabled();
            case ERROR:
                return slf4jLogger.isErrorEnabled();
            case WARN:
                return slf4jLogger.isWarnEnabled();
            default:
                return slf4jLogger.isTraceEnabled();
        }
    }
}
