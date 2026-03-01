package com.team4u.log.core;

import com.team4u.log.support.MockLogAppender;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.event.Level;

/**
 * 日志核心引擎单元测试
 */
public class LogEngineTest {

    private LogEngine engine;

    @Before
    public void setup() {
        engine = LogEngine.getInstance();
        engine.reset();
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
            Circular self = this;
            public Circular getSelf() { return self; }
        }
        
        LogEvent event = new LogEvent().setAction("Error");
        event.getPayload().put("circular", new Circular());

        String json = engine.toJson(event);
        Assert.assertTrue(json.contains("Serialization failed"));
    }

    @Test
    public void testProcessAndOutput() {
        MockLogAppender mockAppender = new MockLogAppender();
        engine.setAppender(mockAppender);

        LogEvent event = new LogEvent().setAction("OutputTest");
        engine.processAndOutput(event);

        Assert.assertEquals(1, mockAppender.size());
        Assert.assertEquals("OutputTest", mockAppender.lastEvent().getAction());
    }

    @Test
    public void testSuppressedLog() {
        MockLogAppender mockAppender = new MockLogAppender();
        engine.setAppender(mockAppender);

        LogEvent event = new LogEvent().setAction("Suppressed").setSuppressed(true);
        engine.processAndOutput(event);

        Assert.assertEquals(0, mockAppender.size());
    }

    @Test
    public void testReset() {
        engine.setMaxLogLength(100);
        engine.reset();
        Assert.assertEquals(5000, engine.getMaxLogLength());
    }
}
