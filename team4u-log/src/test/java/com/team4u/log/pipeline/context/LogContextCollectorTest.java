package com.team4u.log.pipeline.context;

import com.team4u.log.LogContext;
import com.team4u.log.core.LogEvent;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.MDC;

import java.util.Map;

public class LogContextCollectorTest {

    @Before
    public void setup() {
        LogContext.reset();
        LogContext.clearCurrent(); // 确保测试之间线程上下文隔离
        MDC.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testCollectMdc() {
        MDC.put("traceId", "T1");
        MDC.put("X-User-Id", "U1");

        LogEvent event = new LogEvent().setAction("A1");
        Map<String, Object> context = LogContext.getCollector().collect(event);

        Assert.assertEquals("A1", context.get("action"));

        // 验证嵌套 MDC
        Map<String, String> mdc = (Map<String, String>) context.get("mdc");
        Assert.assertNotNull(mdc);
        Assert.assertEquals("T1", mdc.get("traceId"));
        Assert.assertEquals("U1", mdc.get("X-User-Id"));
    }

    @Test
    public void testGlobalAttributes() {
        LogContext.setGlobal("env", "prod");
        LogContext.setGlobal("region", "cn-hangzhou");

        LogEvent event = new LogEvent();
        Map<String, Object> context = LogContext.getCollector().collect(event);

        Assert.assertEquals("prod", context.get("env"));
        Assert.assertEquals("cn-hangzhou", context.get("region"));
    }

    @Test
    public void testCurrentAttributes() {
        LogContext.setCurrent("requestId", "R100");
        LogContext.setCurrent("tempAttr", "T1");

        LogEvent event = new LogEvent();
        Map<String, Object> context = LogContext.getCollector().collect(event);

        Assert.assertEquals("R100", context.get("requestId"));
        Assert.assertEquals("T1", context.get("tempAttr"));

        LogContext.clearCurrent();
        context = LogContext.getCollector().collect(event);
        Assert.assertNull(context.get("requestId"));
    }

    @Test
    public void testCurrentOverrideGlobal() {
        LogContext.setGlobal("env", "prod");
        LogContext.setCurrent("env", "test"); // 当前线程覆盖全局

        LogEvent event = new LogEvent();
        Map<String, Object> context = LogContext.getCollector().collect(event);

        Assert.assertEquals("test", context.get("env"));
    }

    @Test
    public void testCustomContributor() {
        LogContext.addContributor((event, context) -> context.put("ext", "V1"));

        LogEvent event = new LogEvent();
        Map<String, Object> context = LogContext.getCollector().collect(event);

        Assert.assertEquals("V1", context.get("ext"));
    }
}
