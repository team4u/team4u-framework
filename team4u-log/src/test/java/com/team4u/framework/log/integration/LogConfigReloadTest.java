package com.team4u.framework.log.integration;

import com.team4u.framework.config.test.TestConfigContext;
import com.team4u.framework.log.LogBootstrap;
import com.team4u.framework.log.Loggers;
import com.team4u.framework.log.core.LogEvent;
import com.team4u.framework.log.support.TestLogHelper;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.event.Level;

/**
 * 动态配置热重载集成测试
 */
public class LogConfigReloadTest {

    private TestLogHelper logHelper;
    private TestConfigContext testConfigContext;

    @Before
    public void setup() {
        testConfigContext = TestConfigContext.create();
        LogBootstrap.start(LogBootstrap.Options.builder()
                .configManager(testConfigContext.getManager())
                .build());

        logHelper = TestLogHelper.start();
    }

    @After
    public void teardown() {
        logHelper.stop();
        LogBootstrap.stop();
        testConfigContext.destroy();
    }

    @Test
    public void testDynamicMaskReload() throws Exception {
        // 1. 无规则时明文
        Loggers.of(this.getClass()).put("mobile", "13800000000").atInfo().log();
        Assert.assertTrue(logHelper.lastJson().contains("13800000000"));

        // 2. 推送热重载规则
        String config = "{\"java.util.LinkedHashMap\":{\"mobile\":\"MOBILE\"}}";
        testConfigContext.put("team4u.mask.rules", config);
        Thread.sleep(50);

        // 3. 验证生效
        Loggers.of(this.getClass()).put("mobile", "13800000000").atInfo().log();
        Assert.assertTrue("配置热推后应脱敏, JSON 内容为: " + logHelper.lastJson(), logHelper.lastJson().contains("138*****000"));
    }

    @Test
    public void testDynamicDyeingAndFinOpsReload() throws Exception {
        String dyeingConfig = "[" +
                "  { \"id\": \"dye1\", \"condition\": \"meta_action == 'Pay'\", \"targetLevel\": \"DEBUG\" }" +
                "]";
        testConfigContext.put("team4u.log.dyeing", dyeingConfig);

        String finOpsConfig = "{ \"maxLogLength\": 50, \"errorLimitPerSecond\": 5 }";
        testConfigContext.put("team4u.log.finops", finOpsConfig);
        Thread.sleep(50);

        Loggers.of(this.getClass()).action("Pay").success().log();
        LogEvent event = logHelper.lastEvent();

        // 验证染色
        Assert.assertEquals(Level.DEBUG, event.getLevel());
        // 验证 FinOps
        Assert.assertTrue("长度应截断", logHelper.lastJson().length() <= 100); // 宽松验证
    }

    @Test
    public void testDyeingEvenIfLevelDisabled() throws Exception {
        // 验证：即使原始级别被禁用（如 TRACE），命中了染色规则将其提权到 INFO，日志也应输出
        // 这种集成场景确保了 Loggers 和 Interceptor 的正确协作
        String config = "[{\"id\":\"d1\",\"condition\":\"meta_action=='Dye'\",\"targetLevel\":\"INFO\"}]";
        testConfigContext.put("team4u.log.dyeing", config);
        Thread.sleep(50);

        // 故意用一个通常被禁用的级别 TRACE
        Loggers.of(this.getClass()).level(Level.TRACE).action("Dye").log();

        LogEvent event = logHelper.lastEvent();
        Assert.assertNotNull("命中了染色提权，日志应输出", event);
        Assert.assertEquals(Level.INFO, event.getLevel());
    }
}
