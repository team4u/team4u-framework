package com.team4u.log.support;

import com.team4u.log.appender.CompositeLogAppender;
import com.team4u.log.appender.LogAppender;
import com.team4u.log.appender.MemoryLogAppender;
import com.team4u.log.core.LogEngine;
import com.team4u.log.core.LogEvent;

import java.util.List;

/**
 * 结构化日志测试助手
 * <p>
 * 为单元测试提供便捷的日志捕获与断言支持。
 */
public class TestLogHelper {

    private final MemoryLogAppender memoryAppender;

    private TestLogHelper(MemoryLogAppender memoryAppender) {
        this.memoryAppender = memoryAppender;
    }

    /**
     * 开启日志捕获（复合模式）
     * <p>
     * 会将内存捕获器组合进现有的追加器中。
     * 这意味着在单元测试时，控制台依然会打印日志输出，同时也支持通过 helper 进行断言。
     *
     * @return 测试助手实例
     */
    public static TestLogHelper start() {
        LogAppender currentAppender = LogEngine.getInstance().getAppender();
        
        // 如果当前已经是复合 Appender，不再嵌套
        if (currentAppender instanceof CompositeLogAppender) {
            MemoryLogAppender memoryAppender = new MemoryLogAppender();
            ((CompositeLogAppender) currentAppender).addAppender(memoryAppender);
            return new TestLogHelper(memoryAppender);
        }

        MemoryLogAppender memoryAppender = new MemoryLogAppender();
        LogEngine.getInstance().setAppender(new CompositeLogAppender(currentAppender, memoryAppender));

        return new TestLogHelper(memoryAppender);
    }

    /**
     * 获取最近的一条日志
     */
    public LogEvent lastEvent() {
        return memoryAppender.lastEvent();
    }

    /**
     * 获取捕获到的所有日志
     */
    public List<LogEvent> allEvents() {
        return memoryAppender.getEvents();
    }

    /**
     * 将最后一条日志序列化为 JSON 字符串
     */
    public String lastJson() {
        LogEvent event = lastEvent();
        return event == null ? "" : LogEngine.getInstance().toJson(event);
    }

    /**
     * 清空当前捕获到的所有日志
     */
    public void clear() {
        memoryAppender.clear();
    }

    /**
     * 停止捕获并重置 LogEngine 到默认状态
     */
    public void stop() {
        LogEngine.getInstance().reset();
    }
}
