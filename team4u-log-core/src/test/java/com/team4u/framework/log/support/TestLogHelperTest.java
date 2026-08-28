package com.team4u.framework.log.support;

import com.team4u.framework.log.Loggers;
import com.team4u.framework.log.appender.CompositeLogAppender;
import com.team4u.framework.log.appender.LogAppender;
import com.team4u.framework.log.appender.Slf4jLogAppender;
import com.team4u.framework.log.core.LogEngine;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.event.Level;

public class TestLogHelperTest {

    @Before
    public void setup() {
        LogEngine.getInstance().setAppender(new Slf4jLogAppender());
    }

    @After
    public void teardown() {
        LogEngine.getInstance().setAppender(new Slf4jLogAppender());
    }

    @Test
    public void testNestedHelpersStopIndependently() {
        LogAppender originalAppender = LogEngine.getInstance().getAppender();
        TestLogHelper outer = TestLogHelper.start();
        TestLogHelper inner = TestLogHelper.start();

        Loggers.of(getClass()).action("first").level(Level.INFO).log();
        Assert.assertEquals(1, outer.allEvents().size());
        Assert.assertEquals(1, inner.allEvents().size());

        inner.stop();
        Assert.assertTrue(LogEngine.getInstance().getAppender() instanceof CompositeLogAppender);

        Loggers.of(getClass()).action("second").level(Level.INFO).log();
        Assert.assertEquals(2, outer.allEvents().size());
        Assert.assertEquals(1, inner.allEvents().size());

        outer.stop();
        Assert.assertSame(originalAppender, LogEngine.getInstance().getAppender());
    }

    @Test
    public void testStopIsIdempotent() {
        TestLogHelper helper = TestLogHelper.start();
        helper.stop();
        helper.stop();

        Assert.assertNotNull(LogEngine.getInstance().getAppender());
    }
}
