package com.team4u.framework.log.core;

import com.team4u.framework.log.appender.CompositeLogAppender;
import com.team4u.framework.log.appender.LogAppender;
import com.team4u.framework.log.appender.Slf4jLogAppender;
import org.junit.Assert;
import org.junit.Test;

public class EngineAppenderAtomicityTest {

    @Test
    public void globalAppenderCompareAndSetOnlyReplacesExpectedOwner() {
        LogEngine original = LogEngine.getInstance();
        LogAppender initial = original.getAppender();
        LogAppender expected = new Slf4jLogAppender();
        LogAppender update = new Slf4jLogAppender();
        LogEngine.setGlobalAppender(expected);

        try {
            Assert.assertFalse(LogEngine.compareAndSetGlobalAppender(initial, update));
            Assert.assertSame(expected, LogEngine.getInstance().getAppender());
            Assert.assertTrue(LogEngine.compareAndSetGlobalAppender(expected, update));
            Assert.assertSame(update, LogEngine.getInstance().getAppender());
        } finally {
            LogEngine.setGlobalAppender(initial);
        }
    }

    @Test
    public void instanceCompareAndSetOnlyReplacesExpectedAppender() {
        LogEngine engine = LogEngine.builder().build();
        LogAppender expected = new Slf4jLogAppender();
        LogAppender wrong = new Slf4jLogAppender();
        LogAppender update = new CompositeLogAppender();
        engine.setAppender(expected);

        Assert.assertFalse(engine.compareAndSetAppender(wrong, update));
        Assert.assertSame(expected, engine.getAppender());
        Assert.assertTrue(engine.compareAndSetAppender(expected, update));
        Assert.assertSame(update, engine.getAppender());

        engine.setAppender(new Slf4jLogAppender());
    }

    @Test
    public void updateGlobalAppenderTransformRunsAtomically() {
        LogEngine original = LogEngine.getInstance();
        LogAppender initial = original.getAppender();
        LogAppender wrapped = new CompositeLogAppender(initial);

        LogAppender result = LogEngine.updateGlobalAppender(current ->
                current == initial ? wrapped : current);

        try {
            Assert.assertSame(initial, result);
            Assert.assertSame(wrapped, LogEngine.getInstance().getAppender());
            Assert.assertSame(wrapped, LogEngine.updateGlobalAppender(current -> current));
            Assert.assertSame(wrapped, LogEngine.getInstance().getAppender());
        } finally {
            LogEngine.updateGlobalAppender(current -> current == wrapped ? initial : current);
        }
    }
}
