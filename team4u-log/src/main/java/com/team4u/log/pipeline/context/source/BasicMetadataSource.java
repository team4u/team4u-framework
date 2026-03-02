package com.team4u.log.pipeline.context.source;

import com.team4u.log.core.LogEvent;
import com.team4u.log.pipeline.context.LogContextSource;

/**
 * 基础元数据寻值源
 * <p>
 * 处理日志事件的核心字段 (action, level, logger, thread, status, durationMs 等)
 *
 * @author team4u
 */
public class BasicMetadataSource implements LogContextSource {

    @Override
    public Object getValue(LogEvent event, String key) {
        switch (key) {
            case "action":
                return event.getAction();
            case "level":
                return event.getLevel() != null ? event.getLevel().name() : null;
            case "logger":
                return event.getLoggerName();
            case "thread":
                return Thread.currentThread().getName();
            case "status":
                return event.getStatus();
            case "durationMs":
                return event.getDurationMs() >= 0 ? event.getDurationMs() : null;
            default:
                return null;
        }
    }

    @Override
    public int priority() {
        return -100;
    }
}
