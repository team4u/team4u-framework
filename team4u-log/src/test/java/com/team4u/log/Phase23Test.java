package com.team4u.log;

import com.team4u.log.appender.LogAppender;
import com.team4u.log.core.LogEngine;
import com.team4u.log.core.LogEvent;
import com.team4u.log.mask.Mask;
import com.team4u.log.mask.MaskType;
import com.team4u.log.proxy.AutoLogTrace;
import com.team4u.log.proxy.LogProxyFactory;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 2 & 3 综合验收测试
 * <p>
 * 验证：自动日志代理、慢日志预警、异常降噪、基于 Jackson 的极速掩码脱敏。
 */
public class Phase23Test {

    private MockMemoryAppender mockAppender;
    private LogAppender originalAppender;

    @Before
    public void setup() {
        LogEngine.getInstance().reset();
        originalAppender = LogEngine.getInstance().getAppender();
        mockAppender = new MockMemoryAppender();
        LogEngine.getInstance().setAppender(mockAppender);
    }

    @After
    public void teardown() {
        LogEngine.getInstance().setAppender(originalAppender);
    }

    @Test
    public void testAutoLogAndMasking() throws Exception {
        // 1. 创建代理
        UserService service = LogProxyFactory.createProxy(new UserService(), UserService.class);

        // 2. 构造带有敏感信息的请求
        UserReq req = new UserReq("1001", "周杰伦", "13812345678");

        // 3. 执行调用（模拟耗时以触发慢日志）
        String result = service.register(req);
        Assert.assertEquals("SUCCESS", result);

        // 4. 断言捕获到的日志
        Assert.assertEquals(1, mockAppender.capturedEvents.size());
        LogEvent event = mockAppender.capturedEvents.get(0);

        Assert.assertEquals("RegisterUser", event.getAction());
        // 因为 UserService.register 睡眠了 250ms，而注解阈值是 200ms
        Assert.assertEquals(Level.WARN, event.getLevel());
        Assert.assertEquals("slow_success", event.getStatus());

        // 5. 验证序列化掩码输出
        String json = LogEngine.getInstance().toJson(event);
        System.out.println("Generated JSON: " + json);

        Assert.assertTrue("输出应包含脱敏后的姓名", json.contains("周*伦"));
        Assert.assertTrue("输出应包含脱敏后的手机号", json.contains("138****5678"));
        Assert.assertFalse("不应包含原始姓名", json.contains("周杰伦"));
        Assert.assertFalse("不应包含原始手机号", json.contains("13812345678"));
    }

    @Test(expected = RuntimeException.class)
    public void testBusinessExceptionDowngrade() {
        UserService service = LogProxyFactory.createProxy(new UserService(), UserService.class);

        try {
            service.throwBusinessException();
        } finally {
            if (!mockAppender.capturedEvents.isEmpty()) {
                LogEvent event = mockAppender.capturedEvents.get(0);
                Assert.assertEquals(Level.WARN, event.getLevel());
                Assert.assertEquals("business_error", event.getStatus());
            }
        }
    }

    @Test
    public void testNormalError() {
        UserService service = LogProxyFactory.createProxy(new UserService(), UserService.class);

        try {
            service.throwNormalException();
            Assert.fail("Should throw exception");
        } catch (Exception e) {
            LogEvent event = mockAppender.capturedEvents.get(0);
            Assert.assertEquals(Level.ERROR, event.getLevel());
            Assert.assertEquals("failed", event.getStatus());
        }
    }

    /**
     * 带掩码的 DTO
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class UserReq {
        private String id;

        @Mask(MaskType.NAME)
        private String name;

        @Mask(MaskType.PHONE)
        private String phone;
    }

    /**
     * 业务服务
     */
    public static class UserService {
        @AutoLogTrace(action = "RegisterUser", slowThreshold = 200)
        public String register(UserReq req) throws InterruptedException {
            Thread.sleep(250); // 触发慢日志
            return "SUCCESS";
        }

        @AutoLogTrace(ignoreExceptions = {RuntimeException.class})
        public void throwBusinessException() {
            throw new RuntimeException("业务异常");
        }

        @AutoLogTrace
        public void throwNormalException() {
            throw new IllegalStateException("普通异常");
        }
    }

    private static class MockMemoryAppender implements LogAppender {
        public final List<LogEvent> capturedEvents = new ArrayList<>();

        @Override
        public void append(LogEvent event) {
            capturedEvents.add(event);
        }
    }
}
