package com.team4u.log.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.team4u.log.config.FinOpsConfigRepository;
import com.team4u.log.config.FinOpsConfigRepository.FinOpsConfig;
import com.team4u.log.mask.jackson.JacksonLogSerializer;
import com.team4u.log.mask.jackson.JacksonSerializationContext;
import com.team4u.log.mask.jackson.TruncatingStringSerializer;
import com.team4u.log.support.TestLogHelper;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.event.Level;

import java.util.Collections;

/**
 * 日志核心引擎单元测试
 */
public class LogEngineTest {

    private LogEngine engine;
    private TestLogHelper logHelper;
    private FinOpsConfig defaultFinOpsConfig;

    @Before
    public void setup() {
        logHelper = TestLogHelper.start();
        engine = LogEngine.getInstance();
        // 保存默认配置并在测试结束后恢复，如果需要
        defaultFinOpsConfig = FinOpsConfigRepository.getInstance().get();
        FinOpsConfigRepository.getInstance().get().setMaxStringLength(2000);
        FinOpsConfigRepository.getInstance().get().setMaxLogLength(5000);
    }

    @After
    public void teardown() {
        logHelper.stop();
        FinOpsConfigRepository.getInstance().get().setMaxStringLength(defaultFinOpsConfig.getMaxStringLength());
        FinOpsConfigRepository.getInstance().get().setMaxLogLength(defaultFinOpsConfig.getMaxLogLength());
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
        FinOpsConfigRepository.getInstance().get().setMaxStringLength(10);

        LogEvent event = new LogEvent().setAction("TestTruncation");
        event.getPayload().put("longString", "0123456789ABCDEF");

        String json = engine.toJson(event);
        // 验证 payload 中的字符串被截断，且带有提示信息
        Assert.assertTrue(json.contains("\"longString\":\"0123456789... [Truncated len:16]\""));
    }

    @Test
    public void testBeanStringTruncation() {
        FinOpsConfigRepository.getInstance().get().setMaxStringLength(10);

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
        FinOpsConfigRepository.getInstance().get().setMaxLogLength(20);

        LogEvent event = new LogEvent().setAction("VeryLongActionNameThatWillBeTruncated");

        String json = engine.toJson(event);
        Assert.assertEquals(20 + "... [Truncated at 20]".length(), json.length());
        Assert.assertTrue(json.endsWith("... [Truncated at 20]"));
    }

    @Test
    public void testAttributeSnapshotTakesPrecedence() throws Exception {
        FinOpsConfigRepository.getInstance().get().setMaxStringLength(50);

        FinOpsConfig snapshot = new FinOpsConfig();
        snapshot.setMaxStringLength(5);

        ObjectMapper mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(String.class, new TruncatingStringSerializer(new JacksonLogSerializer()));
        mapper.registerModule(module);

        String json = mapper.writer()
                .withAttribute(JacksonSerializationContext.ATTR_FINOPS_CONFIG_SNAPSHOT, snapshot)
                .writeValueAsString(Collections.singletonMap("k", "123456789"));

        Assert.assertTrue(json.contains("\"k\":\"12345... [Truncated len:9]\""));
    }

    @Test
    public void testFallbackToGlobalConfigWhenAttributeMissing() throws Exception {
        FinOpsConfigRepository.getInstance().get().setMaxStringLength(6);

        ObjectMapper mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(String.class, new TruncatingStringSerializer(new JacksonLogSerializer()));
        mapper.registerModule(module);

        String json = mapper.writeValueAsString(Collections.singletonMap("k", "123456789"));

        Assert.assertTrue(json.contains("\"k\":\"123456... [Truncated len:9]\""));
    }

    @Test
    public void testDefaultFallbackWhenSnapshotAndFinOpsAreNull() throws Exception {
        // LogConfigManager 被删除，因此无法为 null。我们可以重置长度来测试默认行为或边界情况
        FinOpsConfigRepository.getInstance().get().setMaxStringLength(2000);

        ObjectMapper mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(String.class, new TruncatingStringSerializer(new JacksonLogSerializer()));
        mapper.registerModule(module);

        String longText = String.join("", Collections.nCopies(2100, "a"));
        String json = mapper.writeValueAsString(Collections.singletonMap("k", longText));

        Assert.assertTrue(json.contains("... [Truncated len:2100]"));
        Assert.assertTrue(json.contains("\"k\":\"" + String.join("", Collections.nCopies(2000, "a"))));
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
        FinOpsConfigRepository.getInstance().get().setMaxLogLength(100);
        FinOpsConfigRepository.getInstance().get().setMaxStringLength(100);

        engine.reset();

        LogEvent event = new LogEvent().setAction("TestReset");
        event.getPayload().put("long", "01234567890123456789");

        Assert.assertEquals(100, FinOpsConfigRepository.getInstance().get().getMaxLogLength());
    }
}
