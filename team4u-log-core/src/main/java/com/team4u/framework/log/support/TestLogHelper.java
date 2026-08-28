package com.team4u.framework.log.support;

import com.team4u.framework.log.appender.CompositeLogAppender;
import com.team4u.framework.log.appender.LogAppender;
import com.team4u.framework.log.appender.MemoryLogAppender;
import com.team4u.framework.log.core.LogEvent;
import com.team4u.framework.log.core.LogEngine;

import java.util.List;

/**
 * Structured logging test helper that survives ownership-safe engine swaps.
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

    public static TestLogHelper start() {
        LogEngine engine = LogEngine.getInstance();
        LogAppender currentAppender = engine.getAppender();
        MemoryLogAppender memoryAppender = new MemoryLogAppender();

        if (currentAppender instanceof CompositeLogAppender) {
            CompositeLogAppender compositeAppender = (CompositeLogAppender) currentAppender;
            compositeAppender.addAppender(memoryAppender);
            return new TestLogHelper(memoryAppender, currentAppender, compositeAppender, false);
        }

        CompositeLogAppender compositeAppender = new CompositeLogAppender(currentAppender, memoryAppender);
        engine.setAppender(compositeAppender);
        return new TestLogHelper(memoryAppender, currentAppender, compositeAppender, true);
    }

    public LogEvent lastEvent() {
        return memoryAppender.lastEvent();
    }

    public List<LogEvent> allEvents() {
        return memoryAppender.getEvents();
    }

    public String lastJson() {
        LogEvent event = lastEvent();
        return event == null ? "" : LogEngine.getInstance().toJson(event);
    }

    public void clear() {
        memoryAppender.clear();
    }

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
