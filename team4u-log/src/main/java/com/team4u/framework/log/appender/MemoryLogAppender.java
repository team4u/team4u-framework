package com.team4u.framework.log.appender;

import com.team4u.framework.log.core.LogEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 内存日志追加器
 * <p>
 * 将日志事件存储在内存队列中。常用于单元测试中断言日志内容，或在线上诊断时查看最近的结构化日志。
 */
public class MemoryLogAppender implements LogAppender {

    private final ReentrantLock lock = new ReentrantLock();
    private final Deque<LogEvent> events = new ArrayDeque<>();
    /**
     * 最大存储容量，默认 1000 条
     */
    private int capacity = 1000;

    @Override
    public void append(LogEvent event) {
        if (event == null) {
            return;
        }

        lock.lock();
        try {
            trimToFit(capacity - 1);
            events.offerLast(event);
        } finally {
            lock.unlock();
        }
    }

    public void setCapacity(int capacity) {
        lock.lock();
        try {
            this.capacity = Math.max(1, capacity);
            trimToFit(this.capacity);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取所有已捕获的日志列表
     *
     * @return 日志事件副本
     */
    public List<LogEvent> getEvents() {
        lock.lock();
        try {
            return new ArrayList<>(events);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取最近一条产生的日志
     *
     * @return 最近的日志事件，若无则返回 null
     */
    public LogEvent lastEvent() {
        lock.lock();
        try {
            return events.peekLast();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 清空当前内存中缓存的所有日志
     */
    public void clear() {
        lock.lock();
        try {
            events.clear();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取当前存储的日志数量
     */
    public int size() {
        lock.lock();
        try {
            return events.size();
        } finally {
            lock.unlock();
        }
    }

    private void trimToFit(int maxSize) {
        int normalizedMaxSize = Math.max(0, maxSize);
        while (events.size() > normalizedMaxSize) {
            events.pollFirst();
        }
    }
}
