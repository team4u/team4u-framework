package com.team4u.log;

import com.team4u.framework.config.test.TestConfigContext;
import com.team4u.log.appender.LogAppender;
import com.team4u.log.core.LogEngine;
import com.team4u.log.core.LogEvent;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.MDC;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 6 终极验收测试：动态配置重载
 * <p>
 * 验证：通过集成 team4u-config-test，验证脱敏规则和染色条件的实时热更新。
 */
public class Phase6ConfigReloadTest {

    private MockMemoryAppender mockAppender;
    private LogAppender originalAppender;
    private TestConfigContext testConfigContext;

    @Before
    public void setup() {
        // 确保单例状态重置，避免测试干扰
        LogEngine.getInstance().reset();

        originalAppender = LogEngine.getInstance().getAppender();
        mockAppender = new MockMemoryAppender();
        LogEngine.getInstance().setAppender(mockAppender);
        MDC.clear();

        // 使用 team4u-config 官方提供的测试上下文工具类代替手动 Fake
        testConfigContext = TestConfigContext.create();
        // 初始化日志自举，绑定 ConfigManager
        LogBootstrap.start(testConfigContext.getManager());
    }

    @After
    public void teardown() {
        LogEngine.getInstance().setAppender(originalAppender);
        testConfigContext.destroy();
    }

    /**
     * 关键场景验证：通过 TestConfigContext 动态推送脱敏规则。
     */
    @Test
    public void testDynamicMaskReload() {
        // 1. 最初没有任何动态脱敏规则
        Loggers.of(this.getClass()).action("InitialTest").kv("mobile", "13800000000").log();
        Assert.assertTrue("未加规则前，手机号应明文输出", mockAppender.lastJson().contains("13800000000"));

        // 2. 模拟配置中心推送热重载 (脱敏 java.util.HashMap 中的 mobile 字段)
        String newConfig = "{" +
                "  \"maskRules\": {" +
                "    \"java.util.HashMap\": { \"mobile\": \"PHONE\" }" +
                "  }" +
                "}";
        testConfigContext.put("team4u.log.config", newConfig);

        // 3. 再次发送相同日志，验证掩码逻辑已实时生效
        Map<String, Object> data = new HashMap<>();
        data.put("mobile", "13800000000");
        Loggers.of(this.getClass()).action("AfterReloadTest").kv("payload", data).log();

        Assert.assertTrue("配置热推后，手机号应脱敏输出", mockAppender.lastJson().contains("138****0000"));
    }

    /**
     * 测试联合规则调整：同时修改染色表达式和脱敏动作。
     */
    @Test
    public void testDynamicDyeingAndFinOpsReload() {
        // 1. 推送初始染色与 FinOps 配置
        String config = "{" +
                "  \"dyeingRules\": [" +
                "    { \"id\": \"vip_dyeing\", \"condition\": \"action == 'Pay'\", \"targetLevel\": \"DEBUG\" }" +
                "  ]," +
                "  \"finOpsConfig\": { \"maxLogLength\": 50 }" +
                "}";
        testConfigContext.put("team4u.log.config", config);

        Loggers.of(this.getClass()).action("Pay").success().log();
        LogEvent event = mockAppender.lastEvent();

        // 验证染色生效：级别提升
        Assert.assertEquals(Level.DEBUG, event.getLevel());
        // 验证 FinOps 生效：长度限制
        Assert.assertEquals(50, LogEngine.getInstance().getMaxLogLength());

        String json = mockAppender.lastJson();
        Assert.assertTrue("单笔日志长度应受到 FinOps 截断限制", json.length() <= 100);
        Assert.assertTrue(json.contains("[Truncated at 50]"));
    }

    private static class MockMemoryAppender implements LogAppender {
        private final List<LogEvent> capturedEvents = new ArrayList<>();

        @Override
        public void append(LogEvent event) {
            capturedEvents.add(event);
        }

        public LogEvent lastEvent() {
            if (capturedEvents.isEmpty()) return null;
            return capturedEvents.get(capturedEvents.size() - 1);
        }

        public String lastJson() {
            LogEvent event = lastEvent();
            return event != null ? LogEngine.getInstance().toJson(event) : "";
        }
    }
}
