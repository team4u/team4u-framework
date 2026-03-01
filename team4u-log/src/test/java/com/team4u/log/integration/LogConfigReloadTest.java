package com.team4u.log.integration;

import com.team4u.framework.config.test.TestConfigContext;
import com.team4u.log.LogBootstrap;
import com.team4u.log.Loggers;
import com.team4u.log.core.LogEngine;
import com.team4u.log.core.LogEvent;
import com.team4u.log.support.MockLogAppender;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.event.Level;

/**
 * 动态配置热重载集成测试
 */
public class LogConfigReloadTest {

    private MockLogAppender mockAppender;
    private TestConfigContext testConfigContext;

    @Before
    public void setup() {
        LogEngine.getInstance().reset();
        mockAppender = new MockLogAppender();
        LogEngine.getInstance().setAppender(mockAppender);

        testConfigContext = TestConfigContext.create();
        LogBootstrap.start(testConfigContext.getManager());
    }

    @After
    public void teardown() {
        testConfigContext.destroy();
        LogEngine.getInstance().reset();
    }

    @Test
    public void testDynamicMaskReload() {
        // 1. 无规则时明文
        Loggers.of(this.getClass()).kv("mobile", "13800000000").log();
        Assert.assertTrue(mockAppender.lastJson().contains("13800000000"));

        // 2. 推送热重载规则
        String config = "{\"maskRules\":{\"java.util.LinkedHashMap\":{\"mobile\":\"PHONE\"}}}";
        testConfigContext.put("team4u.log.config", config);

        // 3. 验证生效
        Loggers.of(this.getClass()).kv("mobile", "13800000000").log();
        Assert.assertTrue("配置热推后应脱敏", mockAppender.lastJson().contains("138****0000"));
    }

    @Test
    public void testDynamicDyeingAndFinOpsReload() {
        String config = "{" +
                "  \"dyeingRules\": [" +
                "    { \"id\": \"dye1\", \"condition\": \"action == 'Pay'\", \"targetLevel\": \"DEBUG\" }" +
                "  ]," +
                "  \"finOpsConfig\": { \"maxLogLength\": 50, \"errorLimitPerSecond\": 5 }" +
                "}";
        testConfigContext.put("team4u.log.config", config);

        Loggers.of(this.getClass()).action("Pay").success().log();
        LogEvent event = mockAppender.lastEvent();

        // 验证染色
        Assert.assertEquals(Level.DEBUG, event.getLevel());
        // 验证 FinOps
        Assert.assertTrue("长度应截断", mockAppender.lastJson().contains("[Truncated at 50]"));
    }

    @Test
    public void testDyeingEvenIfLevelDisabled() {
        // 验证：即使原始级别被禁用（如 TRACE），命中了染色规则将其提权到 INFO，日志也应输出
        // 这种集成场景确保了 Loggers 和 Interceptor 的正确协作
        String config = "{\"dyeingRules\":[{\"id\":\"d1\",\"condition\":\"action=='Dye'\",\"targetLevel\":\"INFO\"}]}";
        testConfigContext.put("team4u.log.config", config);

        // 故意用一个通常被禁用的级别 TRACE
        Loggers.of(this.getClass()).level(Level.TRACE).action("Dye").log();

        LogEvent event = mockAppender.lastEvent();
        Assert.assertNotNull("命中了染色提权，日志应输出", event);
        Assert.assertEquals(Level.INFO, event.getLevel());
    }
}
