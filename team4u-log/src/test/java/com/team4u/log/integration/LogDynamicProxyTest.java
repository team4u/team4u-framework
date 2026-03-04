package com.team4u.log.integration;

import com.team4u.log.core.LogEvent;
import com.team4u.log.mask.config.MaskRuleRepository;
import com.team4u.log.proxy.AutoLogTrace;
import com.team4u.log.proxy.LogProxyFactory;
import com.team4u.log.support.TestLogHelper;
import lombok.Data;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * 动态代理自动日志集成测试
 */
public class LogDynamicProxyTest {

    private TestLogHelper logHelper;

    @Before
    public void setup() {
        // 上一个测试的 teardown() 已通过 LogEngine.reset() 将所有子组件归零
        logHelper = TestLogHelper.start();
    }

    private void refreshRules(Map<String, Map<String, String>> rules) {
        MaskRuleRepository.getInstance().setRuleCache(rules);
    }

    @After
    public void teardown() {
        logHelper.stop();
    }

    @Test
    public void testThirdPartyDynamicProxy() {
        // 1. 创建第三方客户端 (通过子类加注解模拟)
        ThirdPartySmsClient client = new AnnotatedSmsClient();

        // 2. 动态注入规则 (配置了 -parameters 编译参数后，可以直接用原始变量名)
        Map<String, String> userRules = new HashMap<>();
        userRules.put("mobile", "MOBILE");
        userRules.put("appSecret", "PASSWORD");
        Map<String, Map<String, String>> rules = new HashMap<>();
        rules.put(AnnotatedSmsClient.class.getName(), userRules);
        refreshRules(rules);

        // 3. 创建代理
        ThirdPartySmsClient proxy = LogProxyFactory.createProxy(client);

        // 4. 执行调用
        proxy.send("13812345678", "secret123", "Content");

        // 5. 验证
        LogEvent event = logHelper.lastEvent();
        Assert.assertNotNull("日志事件不应为空", event);
        Assert.assertEquals("send", event.getAction());

        String json = logHelper.lastJson();
        Assert.assertTrue("入参手机号应脱敏，JSON 内容为: " + json, json.contains("138*****678"));
        Assert.assertTrue("入参秘钥应脱敏", json.contains("******"));
    }

    @Test
    public void testInterfaceProxy() {
        // 1. 创建接口代理 (接口上加注解)
        ThirdPartyPaymentApi proxy = LogProxyFactory.createProxy(
                (account, amount) -> "SUCCESS",
                ThirdPartyPaymentApi.class);

        // 2. 调用
        proxy.pay("any-account", 100);

        // 3. 验证
        LogEvent event = logHelper.lastEvent();
        Assert.assertNotNull("日志事件不应为空", event);
        Assert.assertEquals("pay", event.getAction());
        Assert.assertEquals("success", event.getStatus());
    }

    @AutoLogTrace
    public interface ThirdPartyPaymentApi {
        String pay(String account, int amount);
    }

    public static class ThirdPartySmsClient {
        public SmsResponse send(String mobile, String appSecret, String content) {
            return new SmsResponse("OK");
        }
    }

    @AutoLogTrace
    public static class AnnotatedSmsClient extends ThirdPartySmsClient {
    }

    @Data
    public static class SmsResponse {
        private String status;

        public SmsResponse() {
        }

        public SmsResponse(String status) {
            this.status = status;
        }
    }
}
