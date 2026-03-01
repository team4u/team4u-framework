package com.team4u.log.proxy;

import com.team4u.framework.config.test.TestConfigContext;
import com.team4u.log.LogBootstrap;
import com.team4u.log.appender.LogAppender;
import com.team4u.log.core.LogEngine;
import com.team4u.log.core.LogEvent;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;

/**
 * 动态配置驱动的日志代理测试 (Phase 7 - 免侵入式代理)
 */
public class LogDynamicProxyTest {

    private MockMemoryAppender mockAppender;
    private LogAppender originalAppender;
    private TestConfigContext testConfigContext;

    @Before
    public void setup() {
        LogEngine.getInstance().reset();
        originalAppender = LogEngine.getInstance().getAppender();
        mockAppender = new MockMemoryAppender();
        LogEngine.getInstance().setAppender(mockAppender);
        MDC.clear();

        testConfigContext = TestConfigContext.create();
        LogBootstrap.start(testConfigContext.getManager());
    }

    @After
    public void teardown() {
        LogEngine.getInstance().setAppender(originalAppender);
        testConfigContext.destroy();
    }

    /**
     * 测试第三方 SDK 无侵入式代理
     */
    @Test
    public void testThirdPartyDynamicProxy() {
        // 1. 推送动态代理规则和脱敏规则
        String className = ThirdPartySmsClient.class.getName();
        String config = "{" +
                "  \"proxyRules\": {" +
                "    \"" + className + "\": {" +
                "      \"methods\": [\"send\"]," +
                "      \"slowThreshold\": 200" +
                "    }" +
                "  }," +
                "  \"maskRules\": {" +
                "    \"*\": {" +
                "      \"mobile\": \"PHONE\"," +
                "      \"appSecret\": \"PASSWORD\"" +
                "    }" +
                "  }" +
                "}";
        testConfigContext.put("team4u.log.config", config);

        // 2. 创建动态代理
        ThirdPartySmsClient rawClient = new ThirdPartySmsClient();
        ThirdPartySmsClient safeClient = LogProxyFactory.createDynamicProxy(rawClient, ThirdPartySmsClient.class);

        // 3. 执行调用
        safeClient.send("13812345678", "sk_live_123abc", "您的验证码是 9527");

        // 4. 断言日志
        LogEvent event = mockAppender.lastEvent();
        Assert.assertNotNull("应当产生一条代理日志", event);
        Assert.assertEquals("send", event.getAction());
        Assert.assertEquals(className, event.getLoggerName());

        String json = mockAppender.lastJson();
        System.out.println("Dynamic Proxy JSON: " + json);

        // 5. 验证脱敏是否生效 (Payload 中的 req 包含参数)
        Assert.assertTrue("入参手机号应脱敏", json.contains("138****5678"));
        Assert.assertTrue("入参凭证应脱敏", json.contains("******"));
        Assert.assertTrue("出参应包含状态", json.contains("OK"));
    }

    /**
     * 测试异常降级策略（由动态配置驱动的无侵入式代理）
     */
    @Test
    public void testDynamicProxyException() {
        // 1. 推送规则，将特定异常加入忽略名单（降级为 WARN）
        String className = ThirdPartySmsClient.class.getName();
        String config = "{" +
                "  \"proxyRules\": {" +
                "    \"" + className + "\": {" +
                "      \"methods\": [\"*\"]," +
                "      \"ignoreExceptions\": [\"java.lang.IllegalArgumentException\"]" +
                "    }" +
                "  }" +
                "}";
        testConfigContext.put("team4u.log.config", config);

        ThirdPartySmsClient safeClient = LogProxyFactory.createDynamicProxy(new ThirdPartySmsClient(),
                ThirdPartySmsClient.class);

        // 2. 触发已知异常
        try {
            safeClient.send("", "", "");
        } catch (IllegalArgumentException ignored) {
        }

        // 3. 验证日志状态
        LogEvent event = mockAppender.lastEvent();
        Assert.assertEquals("business_error", event.getStatus());
        Assert.assertEquals(org.slf4j.event.Level.WARN, event.getLevel());
    }

    /**
     * 模拟第三方 SDK 类
     */
    public static class ThirdPartySmsClient {
        public SmsResponse send(String mobile, String appSecret, String content) {
            if (mobile == null || mobile.isEmpty()) {
                throw new IllegalArgumentException("手机号不能为空");
            }
            return new SmsResponse("OK");
        }
    }

    @Data
    @AllArgsConstructor
    public static class SmsResponse {
        private String status;
    }

    private static class MockMemoryAppender implements LogAppender {
        private final List<LogEvent> capturedEvents = new ArrayList<>();

        @Override
        public void append(LogEvent event) {
            capturedEvents.add(event);
        }

        public LogEvent lastEvent() {
            if (capturedEvents.isEmpty())
                return null;
            return capturedEvents.get(capturedEvents.size() - 1);
        }

        public String lastJson() {
            LogEvent event = lastEvent();
            return event != null ? LogEngine.getInstance().toJson(event) : "";
        }
    }
}
