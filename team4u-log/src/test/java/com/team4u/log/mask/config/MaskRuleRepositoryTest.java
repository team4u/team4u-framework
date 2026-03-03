package com.team4u.log.mask.config;

import com.team4u.log.config.LogConfigManager;
import com.team4u.log.config.LogDynamicConfig;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * 脱敏规则仓库单元测试
 */
public class MaskRuleRepositoryTest {

    private MaskRuleRepository repository;

    @Before
    public void setup() {
        repository = MaskRuleRepository.getInstance();
        // 确保已注册
        LogConfigManager.getInstance().addListener(repository);
        LogConfigManager.getInstance().setCurrentConfig(new LogDynamicConfig());
    }

    private void refreshRules(Map<String, Map<String, String>> rules) {
        LogDynamicConfig config = new LogDynamicConfig();
        config.setMaskRules(rules);
        LogConfigManager.getInstance().setCurrentConfig(config);
    }

    @Test
    public void testPreciseMatch() {
        // 1. 设置特定类规则
        Map<String, String> userRules = new HashMap<>();
        userRules.put("mobile", "PHONE");

        Map<String, Map<String, String>> rules = new HashMap<>();
        rules.put("com.demo.User", userRules);
        refreshRules(rules);

        // 2. 验证精确匹配
        Assert.assertEquals("PHONE", repository.findRule("com.demo.User", "mobile"));
        Assert.assertNull(repository.findRule("com.demo.User", "unknownField"));
        Assert.assertNull(repository.findRule("OtherClass", "mobile"));
    }

    @Test
    public void testGlobalWildcardMatch() {
        // 1. 设置全局规则
        Map<String, String> globalRules = new HashMap<>();
        globalRules.put("mobile", "PHONE");

        Map<String, Map<String, String>> rules = new HashMap<>();
        rules.put("*", globalRules);
        refreshRules(rules);

        // 2. 验证任意类名的 mobile 字段都命中
        Assert.assertEquals("PHONE", repository.findRule("com.any.Class", "mobile"));
        Assert.assertEquals("PHONE", repository.findRule("AnotherClass", "mobile"));
        Assert.assertNull(repository.findRule("AnotherClass", "unknownField"));
    }

    @Test
    public void testPreciseOverrideGlobal() {
        // 1. 设置全局规则：mobile -> PHONE
        Map<String, String> globalRules = new HashMap<>();
        globalRules.put("mobile", "PHONE");

        // 2. 设置特定类规则：User -> mobile -> IDCARD
        Map<String, String> userRules = new HashMap<>();
        userRules.put("mobile", "IDCARD");

        Map<String, Map<String, String>> rules = new HashMap<>();
        rules.put("*", globalRules);
        rules.put("com.demo.User", userRules);
        refreshRules(rules);

        // 3. 验证精确匹配优先
        Assert.assertEquals("IDCARD", repository.findRule("com.demo.User", "mobile"));
        // 4. 验证其它类仍走全局
        Assert.assertEquals("PHONE", repository.findRule("com.other.User", "mobile"));
    }
}
