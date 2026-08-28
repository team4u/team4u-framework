package com.team4u.framework.log.integration;

import com.team4u.framework.config.test.TestConfigContext;
import com.team4u.framework.log.LogBootstrap;
import com.team4u.framework.log.Loggers;
import com.team4u.framework.log.support.TestLogHelper;
import com.team4u.framework.serializer.json.JsonUtil;
import lombok.Data;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Governance masking integration test using explicit Jackson engine assembly.
 */
public class LogMaskingTest {

    private TestLogHelper logHelper;
    private TestConfigContext configContext;

    @Before
    public void setup() {
        LogBootstrap.stop();
        configContext = TestConfigContext.create();
        LogBootstrap.start(LogBootstrap.Options.builder()
                .configManager(configContext.getConfigManager())
                .build());
        logHelper = TestLogHelper.start();
    }

    private void refreshRules(Map<String, Map<String, String>> rules) {
        configContext.put("team4u.mask.rules", JsonUtil.toJsonStr(rules));
    }

    @After
    public void teardown() {
        logHelper.stop();
        LogBootstrap.stop();
        configContext.destroy();
    }

    @Test
    public void testMapMasking() {
        Map<String, String> mapRules = new HashMap<>();
        mapRules.put("password", "PASSWORD");
        Map<String, Map<String, String>> rules = new HashMap<>();
        rules.put("java.util.HashMap", mapRules);
        refreshRules(rules);

        Map<String, Object> data = new HashMap<>();
        data.put("password", "secret123");
        data.put("creditCard", "1234-5678");

        Loggers.of(this.getClass()).put("data", data).atInfo().log();

        String json = logHelper.lastJson();
        Assert.assertTrue("password should be masked", json.contains("\"password\":\"******\""));
        Assert.assertTrue("credit card should not be masked", json.contains("\"creditCard\":\"1234-5678\""));
    }

    @Test
    public void testThirdPartyDtoMasking() {
        ThirdPartyUser user = new ThirdPartyUser("13800138000");

        Map<String, String> userRules = new HashMap<>();
        userRules.put("mobile", "MOBILE");
        Map<String, Map<String, String>> rules = new HashMap<>();
        rules.put(ThirdPartyUser.class.getName(), userRules);
        refreshRules(rules);

        Loggers.of(this.getClass()).put("user", user).atInfo().log();

        String json = logHelper.lastJson();
        Assert.assertTrue("third-party mobile should be masked", json.contains("138*****000"));
    }

    @Test
    public void testGlobalWildcardMasking() {
        Map<String, String> globalRules = new HashMap<>();
        globalRules.put("anyPhone", "MOBILE");
        Map<String, Map<String, String>> rules = new HashMap<>();
        rules.put("*", globalRules);
        refreshRules(rules);

        Map<String, Object> data = new HashMap<>();
        data.put("anyPhone", "13911112222");

        Loggers.of(this.getClass()).put("data", data).atInfo().log();

        String json = logHelper.lastJson();
        Assert.assertTrue("wildcard mobile should be masked", json.contains("139*****222"));
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
