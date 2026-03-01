package com.team4u.log.integration;

import com.team4u.log.core.LogEvent;
import com.team4u.log.mask.Mask;
import com.team4u.log.mask.MaskType;
import com.team4u.log.proxy.AutoLogTrace;
import com.team4u.log.proxy.LogProxyFactory;
import com.team4u.log.support.TestLogHelper;
import lombok.Data;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.event.Level;

/**
 * 自动日志追踪代理集成测试
 */
public class LogProxyTest {

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
    public void testAutoLogAndMasking() throws InterruptedException {
        UserService userService = LogProxyFactory.createProxy(new UserService());

        // 1. 执行业务方法 (触发脱敏与耗时统计)
        UserReq req = new UserReq("1001", "周杰伦", "13812345678");
        String result = userService.register(req);

        // 2. 验证结果
        Assert.assertEquals("SUCCESS", result);

        // 3. 验证日志事件
        LogEvent event = logHelper.lastEvent();
        Assert.assertNotNull("日志事件不应为空", event);
        Assert.assertEquals("RegisterUser", event.getAction());
        Assert.assertTrue("耗时应大于 100ms", event.getDurationMs() >= 100);

        // 4. 验证日志级别 (慢日志应为 WARN)
        Assert.assertEquals(Level.WARN, event.getLevel());
        Assert.assertEquals("slow_success", event.getStatus());

        // 5. 验证序列化掩码输出
        String json = logHelper.lastJson();
        Assert.assertTrue("输出应包含参数名", json.contains("\"arg0\":"));
        Assert.assertTrue("输出应包含脱敏后的姓名", json.contains("**伦"));
        Assert.assertTrue("输出应包含脱敏后的手机号", json.contains("138*****678"));
        Assert.assertFalse("不应包含原始姓名", json.contains("周杰伦"));
        Assert.assertFalse("不应包含原始手机号", json.contains("13812345678"));
    }

    @Test(expected = RuntimeException.class)
    public void testExceptionMasking() {
        UserService userService = LogProxyFactory.createProxy(new UserService());
        try {
            userService.throwBusinessException();
        } catch (RuntimeException e) {
            LogEvent event = logHelper.lastEvent();
            Assert.assertEquals(Level.WARN, event.getLevel());
            Assert.assertEquals("business_error", event.getStatus());
            Assert.assertTrue(logHelper.lastJson().contains("业务异常"));
            throw e;
        }
    }

    @Test
    public void testDefaultAction() {
        OrderService orderService = LogProxyFactory.createProxy(new OrderService());
        orderService.create("ORD-100");

        LogEvent event = logHelper.lastEvent();
        Assert.assertEquals("create", event.getAction()); // 默认取方法名
    }

    public static class OrderService {
        @AutoLogTrace
        public void create(String orderId) {
            // 模拟业务
        }
    }

    @Data
    public static class UserReq {
        private String id;
        @Mask(MaskType.NAME)
        private String name;
        @Mask(MaskType.MOBILE)
        private String phone;

        public UserReq() {
        }

        public UserReq(String id, String name, String phone) {
            this.id = id;
            this.name = name;
            this.phone = phone;
        }
    }

    public static class UserService {
        @AutoLogTrace(action = "RegisterUser", slowThreshold = 100)
        public String register(UserReq arg0) throws InterruptedException {
            Thread.sleep(150); // 触发慢日志
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
}
