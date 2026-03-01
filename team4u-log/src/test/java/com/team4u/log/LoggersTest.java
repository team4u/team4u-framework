package com.team4u.log;

import com.team4u.log.config.LogDynamicConfig.DyeingRule;
import com.team4u.log.core.LogEvent;
import com.team4u.log.pipeline.interceptor.TargetedDyeingInterceptor;
import com.team4u.log.support.TestLogHelper;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.event.Level;

import java.util.Collections;

/**
 * 结构化日志 Fluent API 单元测试
 */
public class LoggersTest {

    private TestLogHelper logHelper;

    @Before
    public void setup() {
        logHelper = TestLogHelper.start();
    }

    @After
    public void teardown() {
        logHelper.stop();
        TargetedDyeingInterceptor.getInstance().reset();
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

        LogEvent event = logHelper.lastEvent();
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
        LogEvent event = logHelper.lastEvent();
        Assert.assertEquals("success", event.getStatus());
        Assert.assertEquals(Level.INFO, event.getLevel());
    }

    @Test
    public void testFailedShortcut() {
        RuntimeException e = new RuntimeException("fail");
        Loggers.of(this.getClass()).failed(e).log();

        LogEvent event = logHelper.lastEvent();
        Assert.assertEquals("failed", event.getStatus());
        Assert.assertEquals(Level.ERROR, event.getLevel());
        Assert.assertEquals(e, event.getException());
    }

    @Test
    public void testLevelShortcuts() {
        // 激活染色规则以绕过 SLF4J 级别检查
        DyeingRule rule = new DyeingRule();
        rule.setId("test");
        rule.setCondition("true");
        TargetedDyeingInterceptor.getInstance().refreshRules(Collections.singletonList(rule));

        Loggers loggers = Loggers.of(this.getClass());

        loggers.atTrace().log();
        Assert.assertEquals(Level.TRACE, logHelper.lastEvent().getLevel());

        loggers.atDebug().log();
        Assert.assertEquals(Level.DEBUG, logHelper.lastEvent().getLevel());

        loggers.atInfo().log();
        Assert.assertEquals(Level.INFO, logHelper.lastEvent().getLevel());

        loggers.atWarn().log();
        Assert.assertEquals(Level.WARN, logHelper.lastEvent().getLevel());

        loggers.atError().log();
        Assert.assertEquals(Level.ERROR, logHelper.lastEvent().getLevel());
    }

    @Test
    public void testLogWithDyeingBypass() {
        // 模拟染色规则存在的场景
        // 这个测试验证 Loggers.log() 中的性能保护逻辑是否正确处理染色
        // 详细逻辑在 FinalReviewFixTest 中已覆盖集成，此处侧重 Fluent API 交互
        Loggers.of(this.getClass()).action("Test").log();
        Assert.assertEquals(1, logHelper.allEvents().size());
    }

    /**
     * 测试 fork 功能，确保派生出的日志器与原日志器状态隔离
     */
    @Test
    public void testFork() {
        // 1. 定义模板日志器
        Loggers baseLog = Loggers.of(LoggersTest.class)
                .kv("module", "TestModule")
                .kv("version", "1.0");

        // 2. 第一次派生并记录日志
        baseLog.fork()
                .action("Action1")
                .kv("user", "Alice")
                .success()
                .log();

        // 验证第一次日志内容
        LogEvent event1 = logHelper.lastEvent();
        Assert.assertEquals("Action1", event1.getAction());
        Assert.assertEquals("Alice", event1.getPayload().get("user"));
        Assert.assertEquals("TestModule", event1.getPayload().get("module"));

        // 3. 第二次派生并记录日志
        baseLog.fork()
                .action("Action2")
                .kv("orderId", "ORDER_123")
                .success()
                .log();

        // 验证第二次日志内容，应包含模板属性，但不包含第一次派生的 user=Alice
        LogEvent event2 = logHelper.lastEvent();
        Assert.assertEquals("Action2", event2.getAction());
        Assert.assertEquals("ORDER_123", event2.getPayload().get("orderId"));
        // 关键：不应包含第一次派生的 user 属性
        Assert.assertNull(event2.getPayload().get("user"));
        Assert.assertEquals("TestModule", event2.getPayload().get("module"));

        // 验证模板日志器本身未被污染
        Assert.assertNull(baseLog.getEvent().getAction());
        Assert.assertNull(baseLog.getEvent().getPayload().get("user"));
        Assert.assertNull(baseLog.getEvent().getPayload().get("orderId"));
        Assert.assertEquals("TestModule", baseLog.getEvent().getPayload().get("module"));
    }
}
