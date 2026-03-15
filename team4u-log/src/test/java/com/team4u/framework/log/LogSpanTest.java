package com.team4u.framework.log;

import com.team4u.framework.log.core.LogEvent;
import com.team4u.framework.log.support.TestLogHelper;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.List;

/**
 * LogSpan 单元测试
 */
public class LogSpanTest {

    private TestLogHelper logHelper;

    @Before
    public void setup() {
        logHelper = TestLogHelper.start();
    }

    @After
    public void teardown() {
        logHelper.stop();
    }

    @Test
    public void testBasicSpan() throws InterruptedException {
        LogSpan span = Loggers.of(LogSpanTest.class)
                .action("TestSpan")
                .put("key", "value")
                .begin();

        Thread.sleep(100);
        span.success().log();

        LogEvent event = logHelper.lastEvent();
        Assert.assertEquals("TestSpan", event.getAction());
        Assert.assertEquals("success", event.getStatus());
        Assert.assertTrue("耗时应大于等于 100ms", event.getDurationMs() >= 100);
        Assert.assertEquals("value", event.getPayload().get("key"));
    }

    @Test
    public void testSpanWithStartLog() throws InterruptedException {
        LogSpan span = Loggers.of(LogSpanTest.class)
                .action("TestSpanWithStart")
                .begin();

        span.logStart();
        Thread.sleep(50);
        span.success().log();

        List<LogEvent> events = logHelper.allEvents();
        Assert.assertEquals(2, events.size());

        LogEvent startEvent = events.get(0);
        Assert.assertEquals("TestSpanWithStart", startEvent.getAction());
        Assert.assertEquals("start", startEvent.getStatus());
        Assert.assertEquals(-1, startEvent.getDurationMs());

        LogEvent endEvent = events.get(1);
        Assert.assertEquals("TestSpanWithStart", endEvent.getAction());
        Assert.assertEquals("success", endEvent.getStatus());
        Assert.assertTrue(endEvent.getDurationMs() >= 50);
    }

    @Test
    public void testSpanWithFailed() {
        LogSpan span = Loggers.of(LogSpanTest.class)
                .action("TestFailedSpan")
                .begin();

        RuntimeException exception = new RuntimeException("error");
        span.failed(exception).log();

        LogEvent event = logHelper.lastEvent();
        Assert.assertEquals("failed", event.getStatus());
        Assert.assertEquals(exception, event.getException());
        Assert.assertTrue(event.getDurationMs() >= 0);
    }

    @Test
    public void testAroundRunnable() {
        Loggers.of(LogSpanTest.class)
                .action("AroundRunnable")
                .around(() -> {
                    // 业务逻辑
                });

        LogEvent event = logHelper.lastEvent();
        Assert.assertEquals("AroundRunnable", event.getAction());
        Assert.assertEquals("success", event.getStatus());
    }

    @Test
    public void testAroundCallable() {
        String result = Loggers.of(LogSpanTest.class)
                .action("AroundCallable")
                .around(() -> "done");

        Assert.assertEquals("done", result);
        LogEvent event = logHelper.lastEvent();
        Assert.assertEquals("AroundCallable", event.getAction());
        Assert.assertEquals("success", event.getStatus());
    }

    @Test(expected = RuntimeException.class)
    public void testAroundFailed() {
        try {
            Loggers.of(LogSpanTest.class)
                    .action("AroundFailed")
                    .around(() -> {
                        throw new RuntimeException("fail");
                    });
        } finally {
            LogEvent event = logHelper.lastEvent();
            Assert.assertEquals("AroundFailed", event.getAction());
            Assert.assertEquals("failed", event.getStatus());
            Assert.assertEquals("fail", event.getException().getMessage());
        }
    }

    @Test(expected = IllegalStateException.class)
    public void testRepeatStartLog() {
        LogSpan span = Loggers.of(LogSpanTest.class).begin();
        span.logStart();
        span.logStart();
    }

    @Test(expected = IllegalStateException.class)
    public void testRepeatLog() {
        LogSpan span = Loggers.of(LogSpanTest.class).begin();
        span.success().log();
        span.success().log();
    }

    @Test
    public void testManualDuration() {
        LogSpan span = Loggers.of(LogSpanTest.class).begin();
        span.put("key", "val");
        Loggers loggers = (Loggers) readField(span, "delegate");
        loggers.duration(999);
        span.success().log();

        LogEvent event = logHelper.lastEvent();
        Assert.assertEquals(999L, event.getDurationMs());
    }

    /**
     * 通过反射读取对象内部字段（替代原有依赖库，减少耦合）
     *
     * @param target    目标对象
     * @param fieldName 字段名称
     * @return 字段值
     */
    private Object readField(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
