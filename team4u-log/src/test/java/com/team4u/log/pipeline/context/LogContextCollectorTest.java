package com.team4u.log.pipeline.context;

import com.team4u.log.LogContext;
import com.team4u.log.core.LogEvent;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.MDC;

import java.util.Map;

/**
 * 日志上下文收集器单元测试 (Pull 模型)
 *
 * @author team4u
 */
public class LogContextCollectorTest {

    @Before
    public void setup() {
        LogContext.reset();
        LogContext.clearCurrent(); // 确保测试之间线程上下文隔离
        MDC.clear();
    }

    @Test
    public void testCollectMdc() {
        MDC.put("traceId", "T1");
        MDC.put("X-User-Id", "U1");

        LogEvent event = new LogEvent().setAction("A1");
        Map<String, Object> context = LogContext.getCollector().collect(event);

        // 基础元数据
        Assert.assertEquals("A1", context.get("action"));

        // 验证高性能 MDC 访问（直接使用原始 key）
        Assert.assertEquals("T1", context.get("traceId"));
        Assert.assertEquals("U1", context.get("X-User-Id"));
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
    public void testCustomSource() {
        // 注册自定义寻值源
        LogContext.addSource((event, key) -> "ext_key".equals(key) ? "V1" : null);

        LogEvent event = new LogEvent();
        Map<String, Object> context = LogContext.getCollector().collect(event);

        Assert.assertEquals("V1", context.get("ext_key"));
        Assert.assertNull(context.get("other_key"));
    }

    @Test
    public void testPayloadPriority() {
        LogContext.setGlobal("k1", "global");
        LogContext.setCurrent("k1", "thread");

        // Payload 优先级最高
        LogEvent event = new LogEvent().kv("k1", "payload");
        Map<String, Object> context = LogContext.getCollector().collect(event);

        Assert.assertEquals("payload", context.get("k1"));
    }
}
