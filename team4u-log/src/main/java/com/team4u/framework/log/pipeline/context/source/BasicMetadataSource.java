package com.team4u.framework.log.pipeline.context.source;

import com.team4u.framework.log.core.LogEvent;
import com.team4u.framework.log.pipeline.context.LogContextSource;

/**
 * 基础元数据寻值源
 * <p>
 * 处理日志事件的核心字段 (action, level, logger, thread, status, durationMs 等)
 *
 * @author jay.wu
 */
public class BasicMetadataSource implements LogContextSource {

    private static final String PREFIX = "meta_";

    @Override
    public Object getValue(LogEvent event, String key) {
        if (key == null) {
            return null;
        }

        switch (key) {
            case PREFIX + "action":
                return event.getAction();
            case PREFIX + "level":
                return event.getLevel() != null ? event.getLevel().name() : null;
            case PREFIX + "logger":
                return event.getLoggerName();
            case PREFIX + "thread":
                return Thread.currentThread().getName();
            case PREFIX + "status":
                return event.getStatus();
            case PREFIX + "durationMs":
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
