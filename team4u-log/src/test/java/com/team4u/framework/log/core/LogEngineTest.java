package com.team4u.framework.log.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.team4u.framework.log.config.FinOpsConfigRepository;
import com.team4u.framework.log.config.FinOpsConfigRepository.FinOpsConfig;
import com.team4u.framework.log.jackson.TruncatingStringSerializer;
import com.team4u.framework.log.support.TestLogHelper;
import com.team4u.framework.mask.jackson.MaskConfig;
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

    @Before
    public void setup() {
        logHelper = TestLogHelper.start();
        engine = LogEngine.getInstance();
        FinOpsConfigRepository.getInstance().stop();
    }

    @After
    public void teardown() {
        logHelper.stop();
        FinOpsConfigRepository.getInstance().stop();
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
        FinOpsConfigRepository repository = FinOpsConfigRepository.getInstance();
        repository.replace(repository.get().withMaxStringLength(10));

        LogEvent event = new LogEvent().setAction("TestTruncation");
        event.getPayload().put("longString", "0123456789ABCDEF");

        String json = engine.toJson(event);
        // 验证 payload 中的字符串被截断，且带有提示信息
        Assert.assertTrue(json.contains("\"longString\":\"0123456789"));
        Assert.assertFalse(json.contains("0123456789ABCDEF"));
    }

    @Test
    public void testBeanStringTruncation() {
        FinOpsConfigRepository repository = FinOpsConfigRepository.getInstance();
        repository.replace(repository.get().withMaxStringLength(10));

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
        FinOpsConfigRepository repository = FinOpsConfigRepository.getInstance();
        repository.replace(repository.get().withMaxLogLength(20));

        LogEvent event = new LogEvent().setAction("VeryLongActionNameThatWillBeTruncated");

        String json = engine.toJson(event);
        Assert.assertEquals(20 + "... [Truncated at 20]".length(), json.length());
        Assert.assertTrue(json.endsWith("... [Truncated at 20]"));
    }

    @Test
    public void testAttributeSnapshotTakesPrecedence() throws Exception {
        FinOpsConfigRepository repository = FinOpsConfigRepository.getInstance();
        repository.replace(repository.get().withMaxStringLength(50));

        FinOpsConfig snapshot = FinOpsConfig.defaults().withMaxStringLength(5);

        ObjectMapper mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(String.class, new TruncatingStringSerializer());
        mapper.registerModule(module);

        MaskConfig maskConfig = new MaskConfig().setMaxStringLength(5);

        String json = mapper.writer()
                .withAttribute(MaskConfig.ATTR_KEY, maskConfig)
                .writeValueAsString(Collections.singletonMap("k", "123456789"));

        Assert.assertTrue(json.contains("\"k\":\"12345... [Truncated len:9]\""));
    }

    @Test
    public void testFallbackToGlobalConfigWhenAttributeMissing() throws Exception {
        FinOpsConfigRepository repository = FinOpsConfigRepository.getInstance();
        repository.replace(repository.get().withMaxStringLength(6));

        ObjectMapper mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(String.class, new TruncatingStringSerializer());
        mapper.registerModule(module);

        String json = mapper.writeValueAsString(Collections.singletonMap("k", "123456789"));

        Assert.assertTrue(json.contains("\"k\":\"123456... [Truncated len:9]\""));
    }

    @Test
    public void testDefaultFallbackWhenSnapshotAndFinOpsAreNull() throws Exception {
        // 验证全局 FinOps 配置的默认截断行为
        FinOpsConfigRepository.getInstance().replace(FinOpsConfig.defaults());

        ObjectMapper mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(String.class, new TruncatingStringSerializer());
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

            // Jackson 默认无法对没有 getter 的私有静态内部类的方法引用进行序列化，
            // 或者我们可以抛出一个异常
            public Object getError() {
                throw new RuntimeException("Forced serialization error");
            }
        }

        LogEvent event = new LogEvent().setAction("Error");
        event.getPayload().put("circular", new Circular());

        String json = engine.toJson(event);
        Assert.assertTrue(json.contains("Serialization failed"));
    }

    @Test
    public void testSerializationErrorFallbackEscapesJson() {
        class Broken {
            public Object getBadValue() {
                throw new RuntimeException("line1\n\"quoted\"");
            }
        }

        LogEvent event = new LogEvent().setAction("bad\"action\n");
        event.getPayload().put("broken", new Broken());

        String json = engine.toJson(event);
        Assert.assertTrue(json.contains("\\n"));
        Assert.assertTrue(json.contains("\\\"quoted\\\""));
        Assert.assertTrue(json.contains("bad\\\"action\\n"));
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
        FinOpsConfigRepository repository = FinOpsConfigRepository.getInstance();
        repository.replace(FinOpsConfig.defaults().withMaxLogLength(100).withMaxStringLength(100));

        engine.reset();

        LogEvent event = new LogEvent().setAction("TestReset");
        event.getPayload().put("long", "01234567890123456789");

        Assert.assertEquals(FinOpsConfig.defaults().getMaxLogLength(), repository.get().getMaxLogLength());
    }
}
