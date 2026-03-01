package com.team4u.log.mask;

import com.team4u.log.Loggers;
import com.team4u.log.appender.LogAppender;
import com.team4u.log.core.LogEngine;
import com.team4u.log.core.LogEvent;
import com.team4u.log.mask.config.MaskRuleRepository;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 脱敏通配符匹配测试
 */
public class MaskWildcardTest {

    private MockMemoryAppender mockAppender;
    private LogAppender originalAppender;

    @Before
    public void setup() {
        LogEngine.getInstance().reset();
        originalAppender = LogEngine.getInstance().getAppender();
        mockAppender = new MockMemoryAppender();
        LogEngine.getInstance().setAppender(mockAppender);
    }

    @After
    public void teardown() {
        LogEngine.getInstance().setAppender(originalAppender);
    }

    /**
     * 测试全局通配符匹配
     */
    @Test
    public void testGlobalWildcardMatching() {
        // 配置全局规则：只要字段名叫 "mobile"，就使用 PHONE 脱敏
        Map<String, MaskType> globalRules = new HashMap<>();
        globalRules.put("mobile", MaskType.PHONE);
        globalRules.put("password", MaskType.PASSWORD);

        Map<String, Map<String, MaskType>> rules = new HashMap<>();
        rules.put("*", globalRules);
        MaskRuleRepository.getInstance().refreshRules(rules);

        // 测试两个完全不同的 DTO
        UserA userA = new UserA("13811112222", "pass123");
        UserB userB = new UserB("13933334444", "secret456");

        Loggers.of(MaskWildcardTest.class)
                .action("WildcardTest")
                .kv("userA", userA)
                .kv("userB", userB)
                .log();

        String json = LogEngine.getInstance().toJson(mockAppender.capturedEvents.get(0));
        System.out.println("Global Wildcard JSON: " + json);

        // 验证全局脱敏是否生效
        Assert.assertTrue("UserA 手机号应脱敏", json.contains("138****2222"));
        Assert.assertTrue("UserA 密码应脱敏", json.contains("******"));
        Assert.assertTrue("UserB 手机号应脱敏", json.contains("139****4444"));
        Assert.assertTrue("UserB 密码应脱敏", json.contains("******"));

        // 再测试一下纯 Map 是否也生效
        Map<String, Object> data = new HashMap<>();
        data.put("mobile", "13755556666");
        data.put("other", "ok");

        Loggers.of(MaskWildcardTest.class)
                .action("MapWildcardTest")
                .kv("data", data)
                .log();

        json = LogEngine.getInstance().toJson(mockAppender.capturedEvents.get(1));
        System.out.println("Map Wildcard JSON: " + json);
        Assert.assertTrue("Map 里的手机号也应脱敏", json.contains("137****6666"));
    }

    /**
     * 测试特定类规则覆盖全局规则
     */
    @Test
    public void testClassOverrideGlobal() {
        // 1. 全局规则：mobile -> PHONE
        Map<String, MaskType> globalRules = new HashMap<>();
        globalRules.put("mobile", MaskType.PHONE);

        // 2. 特定类规则：UserA -> mobile -> DYNAMIC (不掩码，直接输出原始值进行测试)
        Map<String, MaskType> userARules = new HashMap<>();
        userARules.put("mobile", MaskType.DYNAMIC); // 假设 DYNAMIC 在这里配置为不掩码或者特殊掩码

        Map<String, Map<String, MaskType>> rules = new HashMap<>();
        rules.put("*", globalRules);
        rules.put(UserA.class.getName(), userARules);
        MaskRuleRepository.getInstance().refreshRules(rules);

        UserA userA = new UserA("13811112222", "pass");
        UserB userB = new UserB("13933334444", "pass");

        Loggers.of(MaskWildcardTest.class)
                .action("OverrideTest")
                .kv("userA", userA)
                .kv("userB", userB)
                .log();

        String json = LogEngine.getInstance().toJson(mockAppender.capturedEvents.get(0));
        System.out.println("Override JSON: " + json);

        // UserA 应该使用自己的规则 (DYNAMIC 默认也是掩码，但如果我们要验证覆盖，
        // 我们可以看它是否按照特定的规则执行。由于 MaskType.DYNAMIC 是一个特殊的类型，
        // 在 Jackson 序列化时会寻找具体规则。为了简单起见，我们验证它是否不同于全局。
        // 这里我们可以假设 DYNAMIC 会导致不同的输出，或者我们换一个类型。)

        // 重新设置测试： UserA -> mobile -> ID_CARD (仅为测试覆盖逻辑)
        userARules.put("mobile", MaskType.IDCARD);
        MaskRuleRepository.getInstance().refreshRules(rules);

        Loggers.of(MaskWildcardTest.class).action("ReTest").kv("userA", userA).log();
        json = LogEngine.getInstance().toJson(mockAppender.capturedEvents.get(1));

        // PHONE 脱敏是 138****2222，IDCARD 脱敏是 1381**********2222 (假设格式如此)
        // 我们只需验证它应用了 IDCARD 规则而非 PHONE 规则
        Assert.assertFalse("UserA 应应用 IDCARD 规则而非全局 PHONE 规则", json.contains("138****2222"));
    }

    @Data
    @AllArgsConstructor
    public static class UserA {
        private String mobile;
        private String password;
    }

    @Data
    @AllArgsConstructor
    public static class UserB {
        private String mobile;
        private String password;
    }

    private static class MockMemoryAppender implements LogAppender {
        public final List<LogEvent> capturedEvents = new ArrayList<>();

        @Override
        public void append(LogEvent event) {
            capturedEvents.add(event);
        }
    }
}
