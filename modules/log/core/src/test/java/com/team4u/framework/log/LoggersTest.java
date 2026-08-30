package com.team4u.framework.log;

import com.team4u.framework.log.core.LogEngine;
import com.team4u.framework.log.core.LogEvent;
import com.team4u.framework.log.pipeline.LogInterceptor;
import com.team4u.framework.log.support.TestLogHelper;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.event.Level;

import java.util.Collections;
import java.util.List;

public class LoggersTest {

    private TestLogHelper logHelper;

    @Before
    public void setup() {
        logHelper = TestLogHelper.start();
    }

    @After
    public void teardown() {
        logHelper.stop();
        LogEngine.getInstance().reset();
    }

    @Test
    public void fluentApiCapturesStructuredEvent() {
        Loggers.of(getClass())
                .action("Action")
                .status("processing")
                .duration(50)
                .level(Level.INFO)
                .put("key1", "val1")
                .log();

        LogEvent event = logHelper.lastEvent();
        Assert.assertEquals("Action", event.getAction());
        Assert.assertEquals("processing", event.getStatus());
        Assert.assertEquals(50L, event.getDurationMs());
        Assert.assertEquals(Level.INFO, event.getLevel());
        Assert.assertEquals("val1", event.getPayload().get("key1"));
        Assert.assertEquals(getClass().getName(), event.getLoggerName());
    }

    @Test
    public void successAndFailedShortcuts() {
        Loggers.of(getClass()).success().log();
        Assert.assertEquals("success", logHelper.lastEvent().getStatus());
        Assert.assertEquals(Level.INFO, logHelper.lastEvent().getLevel());

        RuntimeException failure = new RuntimeException("fail");
        Loggers.of(getClass()).failed(failure).log();
        Assert.assertEquals("failed", logHelper.lastEvent().getStatus());
        Assert.assertEquals(Level.ERROR, logHelper.lastEvent().getLevel());
        Assert.assertEquals(failure, logHelper.lastEvent().getException());
    }

    @Test
    public void genericInterceptorCanBypassLevelPrecheck() {
        BypassInterceptor interceptor = new BypassInterceptor();
        LogEngine.getInstance().getInterceptorManager().register(interceptor);
        try {
            Loggers.of(getClass()).atTrace().action("Bypass").log();
            Assert.assertEquals("Bypass", logHelper.lastEvent().getAction());
        } finally {
            LogEngine.getInstance().getInterceptorManager().unregister(interceptor);
        }
    }

    @Test
    public void deriveIsolatesTemplateAndChildEvents() {
        Loggers template = Loggers.of(getClass())
                .put("module", "TestModule")
                .put("version", "1.0");

        template.derive().action("Action1").put("user", "Alice").success().log();
        LogEvent first = logHelper.lastEvent();
        Assert.assertEquals("Action1", first.getAction());
        Assert.assertEquals("Alice", first.getPayload().get("user"));
        Assert.assertEquals("TestModule", first.getPayload().get("module"));

        template.derive().action("Action2").put("orderId", "ORDER_123").success().log();
        LogEvent second = logHelper.lastEvent();
        Assert.assertEquals("Action2", second.getAction());
        Assert.assertNull(second.getPayload().get("user"));
        Assert.assertEquals("ORDER_123", second.getPayload().get("orderId"));
        Assert.assertEquals("TestModule", second.getPayload().get("module"));
        Assert.assertNull(template.getEvent().getAction());
    }

    private static class BypassInterceptor implements LogInterceptor {
        @Override
        public boolean handle(LogEvent event) {
            return true;
        }

        @Override
        public boolean shouldBypassLevelPrecheck(LogEvent event) {
            return true;
        }
    }
}
