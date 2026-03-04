package com.team4u.framework.log.pipeline.interceptor;

import com.team4u.framework.log.core.LogEvent;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.MDC;

/**
 * MDC 数据填充拦截器单元测试
 */
public class MdcEnrichInterceptorTest {

    @Before
    public void setUp() {
        MdcEnrichInterceptor.getInstance().reset();
        MDC.clear();
    }

    @Test
    public void testHandleWithDefaultKey() {
        // 测试默认键名: traceId
        MDC.put("traceId", "default-trace-id");
        LogEvent event = new LogEvent();
        MdcEnrichInterceptor.getInstance().handle(event);
        Assert.assertEquals("default-trace-id", event.getTraceId());
    }

    @Test
    public void testHandleWithCustomKey() {
        // 测试自定义键名: requestId
        MdcEnrichInterceptor.getInstance().setTraceIdKey("requestId");
        MDC.put("requestId", "custom-req-id");

        LogEvent event = new LogEvent();
        MdcEnrichInterceptor.getInstance().handle(event);
        Assert.assertEquals("custom-req-id", event.getTraceId());
    }

    @Test
    public void testHandleWithoutAnyId() {
        LogEvent event = new LogEvent();
        MdcEnrichInterceptor.getInstance().handle(event);
        Assert.assertNull(event.getTraceId());
    }

    @Test
    public void testPriority() {
        // 验证优先级是否为最高 (HIGH = -1000)
        Assert.assertEquals(-1000, MdcEnrichInterceptor.getInstance().priority());
    }
}
