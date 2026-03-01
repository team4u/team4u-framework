package com.team4u.log.appender;

import com.team4u.log.core.LogEvent;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * 内存日志追加器
 * <p>
 * 将日志事件存储在内存队列中。常用于单元测试中断言日志内容，或在线上诊断时查看最近的结构化日志。
 */
public class MemoryLogAppender implements LogAppender {

    /**
     * 最大存储容量，默认 1000 条
     */
    @Setter
    private int capacity = 1000;

    private final LinkedBlockingDeque<LogEvent> events = new LinkedBlockingDeque<>();

    @Override
    public void append(LogEvent event) {
        if (events.size() >= capacity) {
            events.pollFirst();
        }
        events.offerLast(event);
    }

    /**
     * 获取所有已捕获的日志列表
     *
     * @return 日志事件副本
     */
    public List<LogEvent> getEvents() {
        return new ArrayList<>(events);
    }

    /**
     * 获取最近一条产生的日志
     *
     * @return 最近的日志事件，若无则返回 null
     */
    public LogEvent lastEvent() {
        return events.peekLast();
    }

    /**
     * 清空当前内存中缓存的所有日志
     */
    public void clear() {
        events.clear();
    }

    /**
     * 获取当前存储的日志数量
     */
    public int size() {
        return events.size();
    }
}
