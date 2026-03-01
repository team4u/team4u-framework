package com.team4u.log.core;

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
}
