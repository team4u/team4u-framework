package com.team4u.log;

import com.team4u.log.appender.LogAppender;
import com.team4u.log.core.LogEngine;
import com.team4u.log.core.LogEvent;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 1 验收测试（增强型：支持结构化断言）
 */
public class LogNextTest {

    private MockMemoryAppender mockAppender;
    private LogAppender originalAppender;

    @Before
    public void setup() {
        // 1. 备份原有的 Appender
        originalAppender = LogEngine.getInstance().getAppender();

        // 2. 注入 Mock Appender 拦截输出
        mockAppender = new MockMemoryAppender();
        LogEngine.getInstance().setAppender(mockAppender);
    }

    @After
    public void teardown() {
        // 3. 恢复现场，防止污染其他测试
        LogEngine.getInstance().setAppender(originalAppender);
        MDC.clear();
    }

    @Test
    public void testFluentLog() {
        // 准备环境与数据
        String traceId = "tid-998877";
        MDC.put("traceId", traceId);
        User heavyUser = new User("周杰伦", "13800138000");

        // 执行业务代码
        Loggers.of(LogNextTest.class)
                .action("CreateOrder")
                .success()
                .duration(120)
                .kv("orderId", "ORD-12345")
                .kv("user", heavyUser)
                .log();

        // ================= 开始精确断言 =================

        // 1. 确保日志确实被捕获了
        Assert.assertEquals("应该只捕获到 1 条日志", 1, mockAppender.capturedEvents.size());

        LogEvent capturedEvent = mockAppender.capturedEvents.get(0);

        // 2. 断言 MDC 提取拦截器 (MdcEnrichInterceptor) 是否生效
        Assert.assertEquals("TraceId 应该被流水线从 MDC 中正确提取", traceId, capturedEvent.getTraceId());

        // 3. 断言基础属性推导是否正确
        Assert.assertEquals("CreateOrder", capturedEvent.getAction());
        Assert.assertEquals("success", capturedEvent.getStatus());
        Assert.assertEquals("成功状态应该自动推导为 INFO 级别", org.slf4j.event.Level.INFO, capturedEvent.getLevel());
        Assert.assertEquals(120L, capturedEvent.getDurationMs());

        // 4. 断言 Payload 载荷是否正确装配
        Assert.assertTrue("载荷应包含 orderId", capturedEvent.getPayload().containsKey("orderId"));
        Assert.assertEquals("ORD-12345", capturedEvent.getPayload().get("orderId"));
        Assert.assertSame("复杂对象应该原样保留在 Payload 中", heavyUser, capturedEvent.getPayload().get("user"));
    }

    /**
     * 自定义测试用内存追加器
     */
    private static class MockMemoryAppender implements LogAppender {
        public final List<LogEvent> capturedEvents = new ArrayList<>();

        @Override
        public void append(LogEvent event) {
            // 直接捕获完整的结构化对象以供断言
            capturedEvents.add(event);
        }
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class User {
        private String name;
        private String phone;
    }
}
