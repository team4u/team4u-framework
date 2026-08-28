package com.team4u.framework.log.core;

import com.team4u.framework.log.support.TestLogHelper;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.event.Level;

import java.util.Arrays;

public class LogEngineTest {

    private LogEngine engine;
    private TestLogHelper logHelper;

    @Before
    public void setup() {
        engine = LogEngine.getInstance();
        engine.reset();
        logHelper = TestLogHelper.start();
    }

    @After
    public void teardown() {
        logHelper.stop();
        engine.reset();
    }

    @Test
    public void jsonMethodUsesConfiguredSerializerWithoutIntroducingAProvider() {
        LogEvent event = new LogEvent().setAction("TestJson").setLevel(Level.INFO).setDurationMs(100);
        event.getPayload().put("key", "value");

        String text = engine.toJson(event);
        Assert.assertSame(engine.getSerializer(), LogEngine.getInstance().getSerializer());
        Assert.assertTrue(text.contains("action=TestJson"));
        Assert.assertTrue(text.contains("durationMs=100"));
        Assert.assertTrue(text.contains("payload"));
    }

    @Test
    public void processAndOutputAppendsPassingEvent() {
        LogEvent event = new LogEvent().setAction("OutputTest");
        engine.processAndOutput(event);

        Assert.assertEquals(1, logHelper.allEvents().size());
        Assert.assertEquals("OutputTest", logHelper.lastEvent().getAction());
    }

    @Test
    public void suppressedLogIsNotAppended() {
        LogEvent event = new LogEvent().setAction("Suppressed").setSuppressed(true);
        engine.processAndOutput(event);

        Assert.assertEquals(0, logHelper.allEvents().size());
    }

    @Test
    public void resetRetainsSerializerAndResetsCoreState() {
        CountingSerializer serializer = new CountingSerializer();
        LogEngine localEngine = LogEngine.builder().serializer(serializer).build();
        localEngine.setAppender(null);

        localEngine.reset();

        Assert.assertSame(serializer, localEngine.getSerializer());
        Assert.assertNotNull(localEngine.getAppender());
    }

    private static final class CountingSerializer implements LogSerializer {
        private int resets;

        @Override
        public String serialize(LogEvent event) {
            return "counted";
        }

        @Override
        public void reset() {
            resets++;
        }
    }
}
