package com.team4u.log.support;

import com.team4u.log.appender.LogAppender;
import com.team4u.log.core.LogEngine;
import com.team4u.log.core.LogEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 模拟日志追加器，用于在测试中捕获日志事件。
 */
public class MockLogAppender implements LogAppender {

    private final List<LogEvent> capturedEvents = new ArrayList<>();

    @Override
    public void append(LogEvent event) {
        capturedEvents.add(event);
    }

    public List<LogEvent> getCapturedEvents() {
        return capturedEvents;
    }

    public LogEvent lastEvent() {
        if (capturedEvents.isEmpty()) {
            return null;
        }
        return capturedEvents.get(capturedEvents.size() - 1);
    }

    public String lastJson() {
        LogEvent event = lastEvent();
        return event != null ? LogEngine.getInstance().toJson(event) : null;
    }

    public void clear() {
        capturedEvents.clear();
    }

    public int size() {
        return capturedEvents.size();
    }
}
