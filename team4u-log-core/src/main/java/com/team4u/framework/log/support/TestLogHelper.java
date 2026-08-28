package com.team4u.framework.log.support;

import com.team4u.framework.log.appender.CompositeLogAppender;
import com.team4u.framework.log.appender.LogAppender;
import com.team4u.framework.log.appender.MemoryLogAppender;
import com.team4u.framework.log.core.LogEvent;
import com.team4u.framework.log.core.LogEngine;

import java.util.List;

/**
 * Structured logging test helper with a private capture wrapper and deterministic cleanup.
 */
public class TestLogHelper {

    private final MemoryLogAppender memoryAppender;
    private final HelperCompositeLogAppender ownedWrapper;

    private TestLogHelper(MemoryLogAppender memoryAppender, HelperCompositeLogAppender ownedWrapper) {
        this.memoryAppender = memoryAppender;
        this.ownedWrapper = ownedWrapper;
    }

    public static TestLogHelper start() {
        MemoryLogAppender memoryAppender = new MemoryLogAppender();
        final TestLogHelper[] helper = new TestLogHelper[1];
        LogEngine.updateGlobalAppender(current -> {
            if (helper[0] != null) {
                return current;
            }
            HelperCompositeLogAppender wrapper = new HelperCompositeLogAppender(current, memoryAppender);
            helper[0] = new TestLogHelper(memoryAppender, wrapper);
            return wrapper;
        });
        return helper[0];
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
        if (ownedWrapper.isStopped()) {
            return;
        }

        LogEngine.updateGlobalAppender(current -> {
            if (ownedWrapper.stopAndMarkIfOwned()) {
                ownedWrapper.removeAppender(memoryAppender);
                LogAppender restoreTarget = collapseStoppedWrappers(ownedWrapper);
                if (current == ownedWrapper) {
                    return restoreTarget;
                }
            }
            return current;
        });
    }

    private static LogAppender collapseStoppedWrappers(HelperCompositeLogAppender wrapper) {
        LogAppender child = singleChild(wrapper);
        while (child instanceof HelperCompositeLogAppender) {
            HelperCompositeLogAppender helperChild = (HelperCompositeLogAppender) child;
            if (!helperChild.isStopped()) {
                break;
            }
            LogAppender nextChild = singleChild(helperChild);
            if (nextChild == null) {
                break;
            }
            child = nextChild;
        }
        return child != null ? child : wrapper;
    }

    private static LogAppender singleChild(CompositeLogAppender wrapper) {
        List<LogAppender> children = wrapper.getAppenders();
        return children.size() == 1 ? children.get(0) : null;
    }

    private static final class HelperCompositeLogAppender extends CompositeLogAppender {
        private volatile boolean stopped;

        private HelperCompositeLogAppender(LogAppender... appenders) {
            super(appenders);
        }

        private boolean isStopped() {
            return stopped;
        }

        /**
         * Marks this wrapper stopped exactly once; the monitor in updateGlobalAppender
         * serializes calls, so a failed compare-and-set means another stop completed.
         */
        private boolean stopAndMarkIfOwned() {
            if (stopped) {
                return false;
            }
            stopped = true;
            return true;
        }
    }
}
