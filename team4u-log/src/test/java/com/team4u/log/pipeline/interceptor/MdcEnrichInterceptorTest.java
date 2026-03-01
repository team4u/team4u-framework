package com.team4u.log.pipeline.interceptor;

import com.team4u.log.core.LogEvent;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.MDC;

/**
 * MDC 数据填充拦截器单元测试
 */
public class MdcEnrichInterceptorTest {

    @Test
    public void testHandleWithTraceId() {
        MDC.put("traceId", "test-trace-id");
        try {
            LogEvent event = new LogEvent();
            MdcEnrichInterceptor.getInstance().handle(event);
            Assert.assertEquals("test-trace-id", event.getTraceId());
        } finally {
            MDC.clear();
        }
    }

    @Test
    public void testHandleWithoutTraceId() {
        MDC.remove("traceId");
        LogEvent event = new LogEvent();
        MdcEnrichInterceptor.getInstance().handle(event);
        Assert.assertNull(event.getTraceId());
    }

    @Test
    public void testPriority() {
        Assert.assertEquals(-1000, MdcEnrichInterceptor.getInstance().priority());
    }
}
