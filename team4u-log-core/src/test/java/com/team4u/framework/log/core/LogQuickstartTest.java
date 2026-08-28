package com.team4u.framework.log.core;

import com.team4u.framework.log.Loggers;
import com.team4u.framework.log.appender.CompositeLogAppender;
import com.team4u.framework.log.appender.LogAppender;
import com.team4u.framework.log.appender.SerializerAwareLogAppender;
import com.team4u.framework.log.appender.Slf4jLogAppender;
import com.team4u.framework.log.pipeline.LogInterceptor;
import com.team4u.framework.log.pipeline.interceptor.RateLimitInterceptor;
import com.team4u.framework.log.support.TestLogHelper;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class LogQuickstartTest {

    private LogEngine originalEngine;

    @Before
    public void setUp() {
        originalEngine = LogEngine.getInstance();
        originalEngine.reset();
    }

    @After
    public void tearDown() {
        LogEngine current = LogEngine.getInstance();
        if (current != originalEngine && current != null) {
            LogEngine.restore(current, originalEngine);
        }
        originalEngine.reset();
    }

    @Test
    public void coreQuickstartUsesSafePlainTextByDefault() {
        LogEvent event = new LogEvent().setAction("core-quickstart");

        Assert.assertTrue(LogEngine.getInstance().toJson(event).contains("action=core-quickstart"));
        Assert.assertFalse(LogEngine.getInstance().toJson(event).contains("\"action\":\"core-quickstart\""));
    }

    @Test
    public void builderEngineIsIndependentAndRunsInjectedInterceptorsOnce() {
        CountingInterceptor first = new CountingInterceptor("first", 2);
        CountingInterceptor second = new CountingInterceptor("second", 1);
        RecordingAppender appender = new RecordingAppender();
        LogSerializer serializer = new PrefixLogSerializer("builder");

        LogEngine engine = LogEngine.builder()
                .serializer(serializer)
                .interceptor(first)
                .interceptors(Arrays.asList(first, second))
                .build();

        engine.setAppender(appender);
        LogEvent event = new LogEvent().setAction("independent");
        engine.processAndOutput(event);

        Assert.assertEquals(1, first.calls.size());
        Assert.assertEquals(1, second.calls.size());
        Assert.assertEquals("first", first.calls.get(0));
        Assert.assertEquals("second", second.calls.get(0));
        Assert.assertEquals("builder:" + event, engine.toJson(event));
        Assert.assertSame(serializer, appender.serializer);
        Assert.assertEquals(1, appender.events.size());
    }

    @Test
    public void globalInstallTransfersAndRebindsAppenderAndRestoreOwnsEngine() {
        RecordingAppender appender = new RecordingAppender();
        originalEngine.setAppender(appender);
        String previousOutput = originalEngine.toJson(new LogEvent().setAction("before"));

        LogEngine replacement = LogEngine.builder()
                .serializer(new PrefixLogSerializer("replacement"))
                .build();
        LogEngine previous = LogEngine.install(replacement);

        Assert.assertSame(originalEngine, previous);
        Assert.assertSame(replacement, LogEngine.getInstance());
        Assert.assertSame(appender, replacement.getAppender());
        Assert.assertEquals("replacement:" + new LogEvent().setAction("event"), appender.serializer.serialize(new LogEvent().setAction("event")));

        Assert.assertTrue(LogEngine.restore(replacement, previous));

        Assert.assertSame(previous, LogEngine.getInstance());
        Assert.assertSame(appender, previous.getAppender());
        Assert.assertEquals(previousOutput, previous.toJson(new LogEvent().setAction("before")));

        Assert.assertFalse(LogEngine.restore(replacement, previous));
        Assert.assertSame(previous, LogEngine.getInstance());
        Assert.assertSame(appender, previous.getAppender());

        List<LogInterceptor> interceptorsBeforeReset = previous.getInterceptorManager().getInterceptors();
        previous.reset();

        Assert.assertTrue(previous.getAppender() instanceof Slf4jLogAppender);
        Assert.assertEquals(new PlainTextLogSerializer().serialize(new LogEvent().setAction("before")),
                previous.toJson(new LogEvent().setAction("before")));
        Assert.assertEquals(0, appender.events.size());
        Assert.assertEquals(interceptorsBeforeReset,
                previous.getInterceptorManager().getInterceptors());
        for (int i = 0; i < RateLimitInterceptor.DEFAULT_ERROR_LIMIT_PER_SECOND; i++) {
            LogEvent rateEvent = new LogEvent().setAction("reset-rate").setException(new RuntimeException("same"));
            Assert.assertTrue(previous.getInterceptorManager().execute(rateEvent));
        }
        LogEvent overflow = new LogEvent().setAction("reset-rate").setException(new RuntimeException("same"));
        Assert.assertFalse(previous.getInterceptorManager().execute(overflow));
        Assert.assertTrue(overflow.isSuppressed());
    }

    @Test
    public void failedRestoreLeavesNewerExternalOwnerUntouched() {
        RecordingAppender externalAppender = new RecordingAppender();
        LogEngine replacement = LogEngine.builder().build();
        LogEngine externalOwner = LogEngine.builder()
                .serializer(new PrefixLogSerializer("external"))
                .build();
        LogEngine.install(replacement);
        LogEngine.install(externalOwner);
        externalOwner.setAppender(externalAppender);

        Assert.assertFalse(LogEngine.restore(replacement, originalEngine));

        Assert.assertSame(externalOwner, LogEngine.getInstance());
        Assert.assertSame(externalAppender, externalOwner.getAppender());
        Assert.assertTrue(externalOwner.getAppender() instanceof RecordingAppender);
    }

    @Test
    public void compositeRebindsCurrentAndFutureChildren() {
        RecordingAppender first = new RecordingAppender();
        RecordingAppender future = new RecordingAppender();
        CompositeLogAppender composite = new CompositeLogAppender(first);
        originalEngine.setAppender(composite);

        LogEngine replacement = LogEngine.builder()
                .serializer(new PrefixLogSerializer("composite"))
                .build();
        LogEngine.install(replacement);
        composite.addAppender(future);

        Assert.assertEquals("composite:" + new LogEvent().setAction("event"), first.serializer.serialize(new LogEvent().setAction("event")));
        Assert.assertEquals("composite:" + new LogEvent().setAction("event"), future.serializer.serialize(new LogEvent().setAction("event")));
        LogEngine.restore(replacement, originalEngine);
    }

    @Test
    public void testHelperKeepsCapturingAcrossEngineSwaps() {
        TestLogHelper helper = TestLogHelper.start();

        LogEngine replacement = LogEngine.builder()
                .serializer(new PrefixLogSerializer("helper"))
                .build();
        LogEngine previous = LogEngine.install(replacement);
        Loggers.of(getClass()).action("captured").success().log();

        Assert.assertEquals("captured", helper.lastEvent().getAction());
        Assert.assertEquals("helper:" + helper.lastEvent(), helper.lastJson());

        LogEngine.restore(replacement, previous);
        helper.stop();
        Assert.assertTrue(LogEngine.getInstance().getAppender() instanceof Slf4jLogAppender);
    }

    @Test
    public void resetKeepsEngineSerializerAndResetsCoreState() {
        CountingInterceptor interceptor = new CountingInterceptor("reset", 1);
        LogEngine engine = LogEngine.builder()
                .serializer(new PrefixLogSerializer("reset"))
                .interceptor(interceptor)
                .build();

        engine.processAndOutput(new LogEvent().setAction("before-reset"));
        engine.reset();

        Assert.assertTrue(engine.getAppender() instanceof Slf4jLogAppender);
        Assert.assertEquals("reset:" + new LogEvent().setAction("event"), engine.toJson(new LogEvent().setAction("event")));
        Assert.assertEquals(1, interceptor.calls.size());
    }

    private static final class PrefixLogSerializer implements LogSerializer {
        private final String prefix;

        private PrefixLogSerializer(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public String serialize(LogEvent event) {
            return prefix + ":" + event;
        }

        @Override
        public void reset() {
        }
    }

    private static final class CountingInterceptor implements LogInterceptor {
        private final String name;
        private final int priority;
        private final List<String> calls = new CopyOnWriteArrayList<>();

        private CountingInterceptor(String name, int priority) {
            this.name = name;
            this.priority = priority;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public boolean handle(LogEvent event) {
            calls.add(name);
            return true;
        }
    }

    private static final class RecordingAppender implements SerializerAwareLogAppender {
        private final List<LogEvent> events = new CopyOnWriteArrayList<>();
        private volatile LogSerializer serializer = new PlainTextLogSerializer();

        @Override
        public void append(LogEvent event) {
            events.add(event);
        }

        @Override
        public void bindSerializer(LogSerializer serializer) {
            this.serializer = serializer;
        }
    }
}
