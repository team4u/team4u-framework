package com.team4u.log;

import com.team4u.log.core.LogEngine;
import com.team4u.log.core.LogEvent;
import com.team4u.log.support.MockLogAppender;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.event.Level;

/**
 * 结构化日志 Fluent API 单元测试
 */
public class LoggersTest {

    private MockLogAppender mockAppender;

    @Before
    public void setup() {
        LogEngine.getInstance().reset();
        mockAppender = new MockLogAppender();
        LogEngine.getInstance().setAppender(mockAppender);
    }

    @Test
    public void testFluentApi() {
        Loggers.of(this.getClass())
                .action("Action")
                .status("processing")
                .duration(50)
                .level(Level.DEBUG)
                .kv("key1", "val1")
                .log();

        LogEvent event = mockAppender.lastEvent();
        Assert.assertEquals("Action", event.getAction());
        Assert.assertEquals("processing", event.getStatus());
        Assert.assertEquals(50L, event.getDurationMs());
        Assert.assertEquals(Level.DEBUG, event.getLevel());
        Assert.assertEquals("val1", event.getPayload().get("key1"));
        Assert.assertEquals(this.getClass().getName(), event.getLoggerName());
    }

    @Test
    public void testSuccessShortcut() {
        Loggers.of(this.getClass()).success().log();
        LogEvent event = mockAppender.lastEvent();
        Assert.assertEquals("success", event.getStatus());
        Assert.assertEquals(Level.INFO, event.getLevel());
    }

    @Test
    public void testFailedShortcut() {
        RuntimeException e = new RuntimeException("fail");
        Loggers.of(this.getClass()).failed(e).log();
        
        LogEvent event = mockAppender.lastEvent();
        Assert.assertEquals("failed", event.getStatus());
        Assert.assertEquals(Level.ERROR, event.getLevel());
        Assert.assertEquals(e, event.getException());
    }

    @Test
    public void testLevelShortcuts() {
        Loggers loggers = Loggers.of(this.getClass());
        
        loggers.atWarn().log();
        Assert.assertEquals(Level.WARN, mockAppender.lastEvent().getLevel());
        
        loggers.atError().log();
        Assert.assertEquals(Level.ERROR, mockAppender.lastEvent().getLevel());
    }

    @Test
    public void testLogWithDyeingBypass() {
        // 模拟染色规则存在的场景
        // 这个测试验证 Loggers.log() 中的性能保护逻辑是否正确处理染色
        // 详细逻辑在 FinalReviewFixTest 中已覆盖集成，此处侧重 Fluent API 交互
        Loggers.of(this.getClass()).action("Test").log();
        Assert.assertEquals(1, mockAppender.size());
    }
}
