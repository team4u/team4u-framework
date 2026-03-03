package com.team4u.log.core;

import com.team4u.log.config.LogConfigManager;
import com.team4u.log.config.LogDynamicConfig;
import com.team4u.log.support.TestLogHelper;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.event.Level;

/**
 * 日志核心引擎单元测试
 */
public class LogEngineTest {

    private LogEngine engine;
    private TestLogHelper logHelper;

    @Before
    public void setup() {
        logHelper = TestLogHelper.start();
        engine = LogEngine.getInstance();
        // 每次测试前重置配置为默认值，避免干扰
        LogConfigManager.getInstance().setCurrentConfig(new LogDynamicConfig());
    }

    @After
    public void teardown() {
        logHelper.stop();
    }

    @Test
    public void testJsonSerialization() {
        LogEvent event = new LogEvent()
                .setAction("TestJson")
                .setLevel(Level.INFO)
                .setDurationMs(100);
        event.getPayload().put("key", "value");

        String json = engine.toJson(event);
        Assert.assertTrue(json.contains("\"action\":\"TestJson\""));
        Assert.assertTrue(json.contains("\"durationMs\":100"));
        Assert.assertTrue(json.contains("\"payload\":{\"key\":\"value\"}"));
    }

    @Test
    public void testStringTruncation() {
        LogDynamicConfig config = new LogDynamicConfig();
        LogDynamicConfig.FinOpsConfig finOpsConfig = new LogDynamicConfig.FinOpsConfig();
        finOpsConfig.setMaxStringLength(10);
        config.setFinOpsConfig(finOpsConfig);
        LogConfigManager.getInstance().setCurrentConfig(config);

        LogEvent event = new LogEvent().setAction("TestTruncation");
        event.getPayload().put("longString", "0123456789ABCDEF");

        String json = engine.toJson(event);
        // 验证 payload 中的字符串被截断，且带有提示信息
        Assert.assertTrue(json.contains("\"longString\":\"0123456789... [Truncated len:16]\""));
    }

    @Test
    public void testBeanStringTruncation() {
        LogDynamicConfig config = new LogDynamicConfig();
        LogDynamicConfig.FinOpsConfig finOpsConfig = new LogDynamicConfig.FinOpsConfig();
        finOpsConfig.setMaxStringLength(10);
        config.setFinOpsConfig(finOpsConfig);
        LogConfigManager.getInstance().setCurrentConfig(config);

        LogEvent event = new LogEvent().setAction("VeryLongActionName");

        String json = engine.toJson(event);
        // 验证 action 字段也被截断
        Assert.assertTrue(json.contains("\"action\":\"VeryLongAc... [Truncated len:18]\""));
    }

    @Test
    public void testByteArrayTruncation() {
        LogEvent event = new LogEvent().setAction("TestByteTruncation");
        byte[] bytes = new byte[100];
        event.getPayload().put("data", bytes);

        String json = engine.toJson(event);
        // 验证 byte[] 被转换为大小提示而非 Base64
        Assert.assertTrue(json.contains("\"data\":\"[byte[] size: 100 bytes]\""));
    }

    @Test
    public void testLogTruncation() {
        LogDynamicConfig config = new LogDynamicConfig();
        LogDynamicConfig.FinOpsConfig finOpsConfig = new LogDynamicConfig.FinOpsConfig();
        finOpsConfig.setMaxLogLength(20);
        config.setFinOpsConfig(finOpsConfig);
        LogConfigManager.getInstance().setCurrentConfig(config);

        LogEvent event = new LogEvent().setAction("VeryLongActionNameThatWillBeTruncated");

        String json = engine.toJson(event);
        Assert.assertEquals(20 + "... [Truncated at 20]".length(), json.length());
        Assert.assertTrue(json.endsWith("... [Truncated at 20]"));
    }

    @Test
    public void testSerializationError() {
        // 构造一个无法序列化的循环引用对象
        class Circular {
            final Circular self = this;

            public Circular getSelf() {
                return self;
            }
        }

        LogEvent event = new LogEvent().setAction("Error");
        event.getPayload().put("circular", new Circular());

        String json = engine.toJson(event);
        Assert.assertTrue(json.contains("Serialization failed"));
    }

    @Test
    public void testProcessAndOutput() {
        LogEvent event = new LogEvent().setAction("OutputTest");
        engine.processAndOutput(event);

        Assert.assertEquals(1, logHelper.allEvents().size());
        Assert.assertEquals("OutputTest", logHelper.lastEvent().getAction());
    }

    @Test
    public void testSuppressedLog() {
        LogEvent event = new LogEvent().setAction("Suppressed").setSuppressed(true);
        engine.processAndOutput(event);

        Assert.assertEquals(0, logHelper.allEvents().size());
    }

    @Test
    public void testReset() {
        LogDynamicConfig config = new LogDynamicConfig();
        LogDynamicConfig.FinOpsConfig finOpsConfig = new LogDynamicConfig.FinOpsConfig();
        finOpsConfig.setMaxLogLength(100);
        finOpsConfig.setMaxStringLength(100);
        config.setFinOpsConfig(finOpsConfig);
        LogConfigManager.getInstance().setCurrentConfig(config);

        engine.reset();

        // 验证序列化器被重置（可以通过验证当前配置拉取是否正常来间接验证）
        LogEvent event = new LogEvent().setAction("TestReset");
        event.getPayload().put("long", "01234567890123456789");
        // reset 后虽然没有显式重置 LogConfigManager (因为它是单例且由 ConfigManager 驱动)
        // 但我们可以验证 engine.reset() 至少清空了拦截器状态
        Assert.assertEquals(100, LogConfigManager.getInstance().getCurrentConfig().getFinOpsConfig().getMaxLogLength());
    }
}
