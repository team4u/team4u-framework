package com.team4u.log.core;

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
        engine.setMaxStringLength(10);
        LogEvent event = new LogEvent().setAction("TestTruncation");
        event.getPayload().put("longString", "0123456789ABCDEF");

        String json = engine.toJson(event);
        // 验证 payload 中的字符串被截断，且带有提示信息
        Assert.assertTrue(json.contains("\"longString\":\"0123456789... [Truncated len:16]\""));
    }

    @Test
    public void testBeanStringTruncation() {
        engine.setMaxStringLength(10);
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
        engine.setMaxLogLength(20);
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
        engine.setMaxLogLength(100);
        engine.setMaxStringLength(100);
        engine.reset();
        Assert.assertEquals(5000, engine.getMaxLogLength());
        Assert.assertEquals(2000, engine.getMaxStringLength());
    }
}
