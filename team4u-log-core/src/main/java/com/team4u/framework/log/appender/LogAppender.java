package com.team4u.framework.log.appender;

import com.team4u.framework.log.core.LogEvent;

/**
 * 日志输出器接口
 * <p>
 * 负责将处理后的日志事件输出到指定目标（如 SLF4J、控制台或远程日志系统）。
 */
public interface LogAppender {

    /**
     * 输出日志事件
     *
     * @param event 日志事件
     */
    void append(LogEvent event);
}
