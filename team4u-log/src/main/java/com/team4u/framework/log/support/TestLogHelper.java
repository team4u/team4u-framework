package com.team4u.framework.log.support;

import com.team4u.framework.log.appender.CompositeLogAppender;
import com.team4u.framework.log.appender.LogAppender;
import com.team4u.framework.log.appender.MemoryLogAppender;
import com.team4u.framework.log.core.LogEngine;
import com.team4u.framework.log.core.LogEvent;

import java.util.List;

/**
 * 结构化日志测试助手
 * <p>
 * 为单元测试提供便捷的日志捕获与断言支持。
 */
public class TestLogHelper {

    private final MemoryLogAppender memoryAppender;
    private final LogAppender originalAppender;
    private final CompositeLogAppender attachedComposite;
    private final boolean ownsComposite;
    private volatile boolean stopped;

    private TestLogHelper(
            MemoryLogAppender memoryAppender,
            LogAppender originalAppender,
            CompositeLogAppender attachedComposite,
            boolean ownsComposite) {
        this.memoryAppender = memoryAppender;
        this.originalAppender = originalAppender;
        this.attachedComposite = attachedComposite;
        this.ownsComposite = ownsComposite;
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
        LogEngine engine = LogEngine.getInstance();
        LogAppender currentAppender = engine.getAppender();
        MemoryLogAppender memoryAppender = new MemoryLogAppender();

        // 如果当前已经是复合 Appender，不再嵌套
        if (currentAppender instanceof CompositeLogAppender) {
            CompositeLogAppender compositeAppender = (CompositeLogAppender) currentAppender;
            compositeAppender.addAppender(memoryAppender);
            return new TestLogHelper(memoryAppender, currentAppender, compositeAppender, false);
        }

        CompositeLogAppender compositeAppender = new CompositeLogAppender(currentAppender, memoryAppender);
        engine.setAppender(compositeAppender);

        return new TestLogHelper(memoryAppender, currentAppender, compositeAppender, true);
    }

    /**
     * 获取最近的一条日志
     *
     * @return 最近的日志事件，若无则返回 null
     */
    public LogEvent lastEvent() {
        return memoryAppender.lastEvent();
    }

    /**
     * 获取捕获到的所有日志
     *
     * @return 日志事件列表
     */
    public List<LogEvent> allEvents() {
        return memoryAppender.getEvents();
    }

    /**
     * 将最后一条日志序列化为 JSON 字符串
     *
     * @return JSON 字符串，若无日志则返回空字符串
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
     * 关闭日志测试助手并恢复全局环境。
     * <p>
     * 安全地移除内存捕获器。如果是本实例主动创建的复合追加器（CompositeAppender），
     * 并且此时只剩下原有的追加器时，会彻底将其还原，消除测试运行的副作用。
     * 保证该方法幂等并具备线程安全性。
     */
    public void stop() {
        if (stopped) {
            return;
        }

        synchronized (this) {
            if (stopped) {
                return;
            }

            LogEngine engine = LogEngine.getInstance();
            attachedComposite.removeAppender(memoryAppender);

            if (ownsComposite
                    && engine.getAppender() == attachedComposite
                    && attachedComposite.getAppenders().size() == 1
                    && attachedComposite.getAppenders().contains(originalAppender)) {
                engine.setAppender(originalAppender);
            }

            stopped = true;
        }
    }
}
