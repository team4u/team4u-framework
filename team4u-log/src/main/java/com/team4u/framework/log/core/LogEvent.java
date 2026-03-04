package com.team4u.framework.log.core;

import lombok.Data;
import lombok.experimental.Accessors;
import org.slf4j.event.Level;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 结构化日志事件模型
 * <p>
 * 用于在拦截器链和输出层之间传递日志上下文数据。
 */
@Data
@Accessors(chain = true)
public class LogEvent {
    /**
     * 日志记录器名称
     */
    private String loggerName;
    /**
     * 日志级别
     */
    private Level level;
    /**
     * 链路追踪 ID
     */
    private String traceId;
    /**
     * 动作名称
     */
    private String action;
    /**
     * 状态（如：success, failed, processing）
     */
    private String status;
    /**
     * 耗时（毫秒），-1 表示未设置
     */
    private long durationMs = -1;
    /**
     * 异常对象
     */
    private Throwable exception;

    /**
     * 业务数据载荷
     */
    private Map<String, Object> payload = new LinkedHashMap<>();
    /**
     * 是否被抑制（例如因限流而不输出）
     */
    private boolean suppressed = false;

    /**
     * 获取业务数据属性
     *
     * @param key 属性键
     * @return 属性值，如果 payload 为空或键不存在则返回 null
     */
    public Object get(String key) {
        return payload != null ? payload.get(key) : null;
    }

    /**
     * 获取业务数据属性（带默认值）
     *
     * @param key          属性键
     * @param defaultValue 默认值
     * @param <T>          属性类型
     * @return 属性值，如果 payload 为空或键不存在则返回默认值
     */
    @SuppressWarnings("unchecked")
    public <T> T getOrDefault(String key, T defaultValue) {
        Object value = get(key);
        return value != null ? (T) value : defaultValue;
    }

    /**
     * 添加业务数据 KV 属性（便捷方法）
     *
     * @param key   属性键
     * @param value 属性值
     * @return 当前实例，支持链式调用
     */
    public LogEvent put(String key, Object value) {
        if (payload == null) {
            payload = new LinkedHashMap<>();
        }
        payload.put(key, value);
        return this;
    }

    /**
     * 批量添加业务数据属性
     *
     * @param kvs 属性 Map
     * @return 当前实例，支持链式调用
     */
    public LogEvent putAll(Map<String, Object> entries) {
        if (entries != null) {
            if (payload == null) {
                payload = new LinkedHashMap<>();
            }
            payload.putAll(entries);
        }
        return this;
    }

    /**
     * 派生当前日志事件状态，生成一个新的独立实例。
     * <p>
     * 常用于将当前 LogEvent 作为模板（Template），
     * 预置公共的 KV 属性或动作，在具体使用时 derive 出来，避免状态污染。
     * 特别说明：payload (KV 映射) 会进行浅拷贝（创建新的 Map）。
     *
     * @return 派生的 LogEvent 实例
     */
    public LogEvent derive() {
        LogEvent copy = new LogEvent();
        copy.setLoggerName(this.loggerName);
        copy.setLevel(this.level);
        copy.setTraceId(this.traceId);
        copy.setAction(this.action);
        copy.setStatus(this.status);
        copy.setDurationMs(this.durationMs);
        copy.setException(this.exception);
        copy.setSuppressed(this.suppressed);

        if (this.payload != null) {
            copy.setPayload(new LinkedHashMap<>(this.payload));
        }
        return copy;
    }
}
