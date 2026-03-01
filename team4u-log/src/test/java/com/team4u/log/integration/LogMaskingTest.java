package com.team4u.log.integration;

import com.team4u.log.Loggers;
import com.team4u.log.mask.MaskType;
import com.team4u.log.mask.config.MaskRuleRepository;
import com.team4u.log.support.TestLogHelper;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * 日志脱敏功能集成测试
 */
public class LogMaskingTest {

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
    public void testMapMaskingWithDefaultRules() {
        Map<String, Object> data = new HashMap<>();
        data.put("password", "secret123");
        data.put("creditCard", "1234-5678");

        Loggers.of(this.getClass()).kv("data", data).log();

        String json = logHelper.lastJson();
        Assert.assertTrue("密码应脱敏", json.contains("\"password\":\"******\""));
        Assert.assertTrue("信用卡应脱敏", json.contains("\"creditCard\":\"***\""));
    }

    @Test
    public void testThirdPartyDtoMasking() {
        ThirdPartyUser user = new ThirdPartyUser("13800138000");

        // 动态注入规则
        Map<String, MaskType> userRules = new HashMap<>();
        userRules.put("mobile", MaskType.PHONE);
        Map<String, Map<String, MaskType>> rules = new HashMap<>();
        rules.put(ThirdPartyUser.class.getName(), userRules);
        MaskRuleRepository.getInstance().refreshRules(rules);

        Loggers.of(this.getClass()).kv("user", user).log();

        String json = logHelper.lastJson();
        Assert.assertTrue("第三方 DTO 手机号应脱敏", json.contains("138****8000"));
    }

    @Test
    public void testGlobalWildcardMasking() {
        // 配置全局规则
        Map<String, MaskType> globalRules = new HashMap<>();
        globalRules.put("anyPhone", MaskType.PHONE);
        Map<String, Map<String, MaskType>> rules = new HashMap<>();
        rules.put("*", globalRules);
        MaskRuleRepository.getInstance().refreshRules(rules);

        Map<String, Object> data = new HashMap<>();
        data.put("anyPhone", "13911112222");

        Loggers.of(this.getClass()).kv("data", data).log();

        String json = logHelper.lastJson();
        Assert.assertTrue("通配符匹配手机号应脱敏", json.contains("139****2222"));
    }

    @Data
    @AllArgsConstructor
    public static class ThirdPartyUser {
        private String mobile;
    }
}
