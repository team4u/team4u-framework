package com.team4u.log.integration;

import com.team4u.framework.config.test.TestConfigContext;
import com.team4u.log.LogBootstrap;
import com.team4u.log.core.LogEvent;
import com.team4u.log.mask.MaskType;
import com.team4u.log.mask.config.MaskRuleRepository;
import com.team4u.log.proxy.LogProxyFactory;
import com.team4u.log.support.TestLogHelper;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.MDC;
import org.slf4j.event.Level;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 动态配置驱动的日志代理集成测试（针对第三方类库）
 */
public class LogDynamicProxyTest {

    private TestLogHelper logHelper;
    private TestConfigContext testConfigContext;

    @Before
    public void setup() {
        MDC.clear();

        testConfigContext = TestConfigContext.create();
        LogBootstrap.start(testConfigContext.getManager());

        logHelper = TestLogHelper.start();
    }

    @After
    public void teardown() {
        testConfigContext.destroy();
        logHelper.stop();
    }

    @Test
    public void testThirdPartyDynamicProxy() {
        // 1. 同步注入脱敏规则
        Map<String, MaskType> globalRules = new HashMap<>();
        // 针对参数名 "mobile" 配置脱敏（若编译带参数名）
        globalRules.put("mobile", MaskType.PHONE);
        // 针对兜底参数名 "arg0" 配置脱敏（若编译不带参数名）
        globalRules.put("arg0", MaskType.PHONE);
        MaskRuleRepository.getInstance().refreshRules(Collections.singletonMap("*", globalRules));

        // 2. 推送动态代理规则
        String className = ThirdPartySmsClient.class.getName();
        String config = "{\"proxyRules\":{\"" + className + "\":{\"methods\":[\"send\"]}}}";
        testConfigContext.put("team4u.log.config", config);

        // 3. 执行
        ThirdPartySmsClient rawClient = new ThirdPartySmsClient();
        ThirdPartySmsClient safeClient = LogProxyFactory.createDynamicProxy(rawClient);
        safeClient.send("13812345678", "sk_123", "Content");

        // 4. 验证
        LogEvent event = logHelper.lastEvent();
        Assert.assertNotNull(event);
        Assert.assertEquals("send", event.getAction());

        String json = logHelper.lastJson();
        // 验证入参已从数组变为对象，且至少命中了一种脱敏（mobile 或 arg0）
        Assert.assertTrue("入参手机号应脱敏，JSON 内容为: " + json, json.contains("138****5678"));
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

        ThirdPartySmsClient safeClient = LogProxyFactory.createDynamicProxy(new ThirdPartySmsClient());
        try {
            safeClient.send("", "", "");
        } catch (IllegalArgumentException ignored) {
        }

        LogEvent event = logHelper.lastEvent();
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
