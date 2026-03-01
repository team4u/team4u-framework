package com.team4u.log.integration;

import com.team4u.framework.config.test.TestConfigContext;
import com.team4u.log.LogBootstrap;
import com.team4u.log.core.LogEngine;
import com.team4u.log.core.LogEvent;
import com.team4u.log.proxy.LogProxyFactory;
import com.team4u.log.support.MockLogAppender;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.MDC;
import org.slf4j.event.Level;

/**
 * 动态配置驱动的日志代理集成测试（针对第三方类库）
 */
public class LogDynamicProxyTest {

    private MockLogAppender mockAppender;
    private TestConfigContext testConfigContext;

    @Before
    public void setup() {
        LogEngine.getInstance().reset();
        mockAppender = new MockLogAppender();
        LogEngine.getInstance().setAppender(mockAppender);
        MDC.clear();

        testConfigContext = TestConfigContext.create();
        LogBootstrap.start(testConfigContext.getManager());
    }

    @After
    public void teardown() {
        testConfigContext.destroy();
        LogEngine.getInstance().reset();
    }

    @Test
    public void testThirdPartyDynamicProxy() {
        // 1. 推送动态代理规则
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

        // 2. 创建并执行动态代理
        ThirdPartySmsClient rawClient = new ThirdPartySmsClient();
        ThirdPartySmsClient safeClient = LogProxyFactory.createDynamicProxy(rawClient, ThirdPartySmsClient.class);
        safeClient.send("13812345678", "sk_123", "Content");

        // 3. 验证日志
        LogEvent event = mockAppender.lastEvent();
        Assert.assertNotNull(event);
        Assert.assertEquals("send", event.getAction());

        String json = mockAppender.lastJson();
        Assert.assertTrue("入参手机号应脱敏", json.contains("138****5678"));
        Assert.assertTrue("入参凭证应脱敏", json.contains("******"));
    }

    @Test
    public void testDynamicProxyException() {
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

        ThirdPartySmsClient safeClient = LogProxyFactory.createDynamicProxy(new ThirdPartySmsClient(), ThirdPartySmsClient.class);
        try {
            safeClient.send("", "", "");
        } catch (IllegalArgumentException ignored) {}

        LogEvent event = mockAppender.lastEvent();
        Assert.assertEquals("business_error", event.getStatus());
        Assert.assertEquals(Level.WARN, event.getLevel());
    }

    public static class ThirdPartySmsClient {
        public SmsResponse send(String mobile, String appSecret, String content) {
            if (mobile == null || mobile.isEmpty()) {
                throw new IllegalArgumentException("Empty mobile");
            }
            return new SmsResponse("OK");
        }
    }

    @Data
    @AllArgsConstructor
    public static class SmsResponse {
        private String status;
    }
}
