package com.team4u.log.mask.config;

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
        repository.reset();
    }

    @Test
    public void testPreciseMatch() {
        // 验证预置的默认规则
        Assert.assertEquals("PASSWORD", repository.findRule("java.util.HashMap", "password"));
        Assert.assertEquals("DYNAMIC_CARD", repository.findRule("java.util.HashMap", "creditCard"));
    }

    @Test
    public void testGlobalWildcardMatch() {
        // 1. 设置全局规则
        Map<String, String> globalRules = new HashMap<>();
        globalRules.put("mobile", "PHONE");

        Map<String, Map<String, String>> rules = new HashMap<>();
        rules.put("*", globalRules);
        repository.refreshRules(rules);

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
        repository.refreshRules(rules);

        // 3. 验证精确匹配优先
        Assert.assertEquals("IDCARD", repository.findRule("com.demo.User", "mobile"));
        // 4. 验证其它类仍走全局
        Assert.assertEquals("PHONE", repository.findRule("com.other.User", "mobile"));
    }

    @Test
    public void testReset() {
        // 1. 修改规则
        repository.refreshRules(new HashMap<>());
        Assert.assertNull(repository.findRule("java.util.HashMap", "password"));

        // 2. 重置
        repository.reset();
        Assert.assertEquals("PASSWORD", repository.findRule("java.util.HashMap", "password"));
    }
}
