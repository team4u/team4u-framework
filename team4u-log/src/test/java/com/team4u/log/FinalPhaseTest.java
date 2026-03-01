package com.team4u.log;

import com.team4u.log.appender.LogAppender;
import com.team4u.log.config.LogDynamicConfig;
import com.team4u.log.core.LogEngine;
import com.team4u.log.core.LogEvent;
import com.team4u.log.mask.MaskType;
import com.team4u.log.mask.config.MaskRuleRepository;
import com.team4u.log.pipeline.interceptor.TargetedDyeingInterceptor;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.MDC;
import org.slf4j.event.Level;

import java.util.*;

/**
 * Phase 4 & 5 最终验收测试
 * <p>
 * 验证：第三方类动态规则脱敏、Map 掩码脱敏、靶向动态染色、雪崩限流保护。
 */
public class FinalPhaseTest {

    private MockMemoryAppender mockAppender;
    private LogAppender originalAppender;

    @Before
    public void setup() {
        LogEngine.getInstance().reset();
        originalAppender = LogEngine.getInstance().getAppender();
        mockAppender = new MockMemoryAppender();
        LogEngine.getInstance().setAppender(mockAppender);
        MDC.clear();
    }

    @After
    public void teardown() {
        LogEngine.getInstance().setAppender(originalAppender);
    }

    /**
     * 测试第三方 DTO 动态规则脱敏 (Phase 4)
     */
    @Test
    public void testThirdPartyDtoMasking() {
        ThirdPartyUser user = new ThirdPartyUser("9527", "13888888888");
        String className = user.getClass().getName();
        // 动态注入当前真实类名，模拟配置加载
        Map<String, MaskType> userRules = new HashMap<>();
        userRules.put("mobile", MaskType.PHONE);
        Map<String, Map<String, MaskType>> rules = new HashMap<>();
        rules.put(className, userRules);
        MaskRuleRepository.getInstance().refreshRules(rules);

        Loggers.of(FinalPhaseTest.class)
                .action("ThirdPartyTest")
                .kv("user", user)
                .log();

        LogEvent event = mockAppender.capturedEvents.get(0);
        String json = LogEngine.getInstance().toJson(event);
        System.out.println("ThirdParty JSON: " + json);

        // 根据 MaskRuleRepository 中的配置，mobile 字段会被脱敏为 PHONE 类型
        Assert.assertTrue("JSON 应脱敏手机号", json.contains("138****8888"));
    }

    /**
     * 测试 Map 嵌套掩码脱敏 (Phase 4)
     */
    @Test
    public void testMapMasking() {
        Map<String, Object> data = new HashMap<>();
        data.put("password", "secret123");
        data.put("other", "normal_data");

        Loggers.of(FinalPhaseTest.class)
                .action("MapTest")
                .kv("payload", data)
                .log();

        LogEvent event = mockAppender.capturedEvents.get(0);
        String json = LogEngine.getInstance().toJson(event);
        System.out.println("Map JSON: " + json);

        Assert.assertTrue("JSON 应脱敏密码", json.contains("******"));
        Assert.assertTrue("JSON 应保留普通数据", json.contains("normal_data"));
    }

    /**
     * 测试靶向动态染色 (Phase 5)
     */
    @Test
    public void testTargetedDyeing() {
        // 配置动态染色规则
        LogDynamicConfig.DyeingRule rule = new LogDynamicConfig.DyeingRule();
        rule.setId("vip_user_debug");
        rule.setCondition("(action == 'Pay' || userId == '10086') && level == 'ERROR'");
        rule.setTargetLevel(Level.DEBUG);
        TargetedDyeingInterceptor.getInstance().refreshRules(Collections.singletonList(rule));

        // 条件：userId == '10086' || action == 'Pay'
        MDC.put("X-User-Id", "10086");

        Loggers.of(FinalPhaseTest.class)
                .action("QueryProfile")
                .failed(new RuntimeException("DyeingTest"))
                .log();

        LogEvent event = mockAppender.capturedEvents.get(0);
        // 原本成功日志是 INFO，因为命中了 userId，自动上色为 DEBUG
        Assert.assertEquals(Level.DEBUG, event.getLevel());
        Assert.assertTrue(event.getPayload().containsKey("dyeingRuleMatched"));
    }

    /**
     * 测试防雪崩频控限流 (Phase 5)
     */
    @Test
    public void testRateLimiting() {
        // 连续抛出 15 个同样的异常
        for (int i = 0; i < 15; i++) {
            Loggers.of(FinalPhaseTest.class)
                    .action("DatabaseCall")
                    .failed(new RuntimeException("DB_DOWN"))
                    .log();
        }

        // 默认拦截器限制 1 秒内同类异常最多 10 条
        Assert.assertTrue("捕获到的日志条数应小于等于 10", mockAppender.capturedEvents.size() <= 10);
    }

    /**
     * 模拟一个外部不可修改的 DTO (放在 com.demo 包下模拟第三方库)
     */
    @Data
    @AllArgsConstructor
    public static class ThirdPartyUser {
        private String id;
        private String mobile; // 对应规则库中的 com.demo.ThirdPartyUser.mobile
    }

    private static class MockMemoryAppender implements LogAppender {
        public final List<LogEvent> capturedEvents = new ArrayList<>();

        @Override
        public void append(LogEvent event) {
            capturedEvents.add(event);
        }
    }
}
