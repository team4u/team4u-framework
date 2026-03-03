package com.team4u.log.integration;

import com.team4u.log.Loggers;
import com.team4u.log.config.LogConfigManager;
import com.team4u.log.config.LogDynamicConfig;
import com.team4u.log.mask.config.MaskRuleRepository;
import com.team4u.log.support.TestLogHelper;
import lombok.Data;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * 掩码功能集成测试
 */
public class LogMaskingTest {

    private TestLogHelper logHelper;

    @Before
    public void setup() {
        logHelper = TestLogHelper.start();
        // 确保单例已注册
        LogConfigManager.getInstance().addListener(MaskRuleRepository.getInstance());
        LogConfigManager.getInstance().setCurrentConfig(new LogDynamicConfig());
    }

    private void refreshRules(Map<String, Map<String, String>> rules) {
        LogDynamicConfig config = new LogDynamicConfig();
        config.setMaskRules(rules);
        LogConfigManager.getInstance().setCurrentConfig(config);
    }

    @After
    public void teardown() {
        logHelper.stop();
    }

    @Test
    public void testMapMasking() {
        // 手动设置规则
        Map<String, String> mapRules = new HashMap<>();
        mapRules.put("password", "PASSWORD");
        Map<String, Map<String, String>> rules = new HashMap<>();
        rules.put("java.util.HashMap", mapRules);
        refreshRules(rules);

        Map<String, Object> data = new HashMap<>();
        data.put("password", "secret123");
        data.put("creditCard", "1234-5678");

        Loggers.of(this.getClass()).put("data", data).log();

        String json = logHelper.lastJson();
        Assert.assertTrue("密码应脱敏", json.contains("\"password\":\"******\""));
        Assert.assertTrue("信用卡不应脱敏", json.contains("\"creditCard\":\"1234-5678\""));
    }

    @Test
    public void testThirdPartyDtoMasking() {
        ThirdPartyUser user = new ThirdPartyUser("13800138000");

        // 动态注入规则
        Map<String, String> userRules = new HashMap<>();
        userRules.put("mobile", "MOBILE");
        Map<String, Map<String, String>> rules = new HashMap<>();
        rules.put(ThirdPartyUser.class.getName(), userRules);
        refreshRules(rules);

        Loggers.of(this.getClass()).put("user", user).log();

        String json = logHelper.lastJson();
        Assert.assertTrue("第三方 DTO 手机号应脱敏", json.contains("138*****000"));
    }

    @Test
    public void testGlobalWildcardMasking() {
        // 配置全局规则
        Map<String, String> globalRules = new HashMap<>();
        globalRules.put("anyPhone", "MOBILE");
        Map<String, Map<String, String>> rules = new HashMap<>();
        rules.put("*", globalRules);
        refreshRules(rules);

        Map<String, Object> data = new HashMap<>();
        data.put("anyPhone", "13911112222");

        Loggers.of(this.getClass()).put("data", data).log();

        String json = logHelper.lastJson();
        Assert.assertTrue("通配符匹配手机号应脱敏", json.contains("139*****222"));
    }

    @Data
    public static class ThirdPartyUser {
        private String mobile;

        public ThirdPartyUser() {
        }

        public ThirdPartyUser(String mobile) {
            this.mobile = mobile;
        }
    }
}
