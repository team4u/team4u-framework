package com.team4u.framework.log.pipeline.context;

import com.team4u.framework.log.LogContext;
import com.team4u.framework.log.core.LogEvent;
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
        MDC.clear();
    }

    @Test
    public void testCollectMdc() {
        MDC.put("traceId", "T1");
        MDC.put("X-User-Id", "U1");

        LogEvent event = new LogEvent().setAction("A1");
        Map<String, Object> context = LogContext.getCollector().collect(event);

        // 基础元数据仅支持前缀 key
        Assert.assertNull(context.get("action"));
        Assert.assertEquals("A1", context.get("meta_action"));

        // 验证高性能 MDC 访问（直接使用原始 key）
        Assert.assertEquals("T1", context.get("traceId"));
        Assert.assertEquals("U1", context.get("X-User-Id"));
    }

    @Test
    public void testMetadataPrefixAvoidConflict() {
        LogEvent event = new LogEvent()
                .setAction("A1")
                .put("action", "payload-action");

        Map<String, Object> context = LogContext.getCollector().collect(event);

        // 无前缀时优先命中 Payload
        Assert.assertEquals("payload-action", context.get("action"));
        // 使用前缀可稳定拿到元数据
        Assert.assertEquals("A1", context.get("meta_action"));
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
        MDC.put("k1", "mdc");

        // Payload 优先级最高
        LogEvent event = new LogEvent().put("k1", "payload");
        Map<String, Object> context = LogContext.getCollector().collect(event);

        Assert.assertEquals("payload", context.get("k1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testGetWholePayload() {
        LogEvent event = new LogEvent().put("k1", "v1").put("k2", "v2");
        Map<String, Object> context = LogContext.getCollector().collect(event);

        // 验证通过 "payload" 键获取完整的 Map
        Map<String, Object> payload = (Map<String, Object>) context.get("payload");
        Assert.assertNotNull(payload);
        Assert.assertEquals("v1", payload.get("k1"));
        Assert.assertEquals("v2", payload.get("k2"));
    }
}
