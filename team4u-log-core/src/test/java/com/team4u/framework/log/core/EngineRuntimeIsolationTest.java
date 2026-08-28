package com.team4u.framework.log.core;

import com.team4u.framework.log.pipeline.LogInterceptor;
import com.team4u.framework.log.pipeline.LogInterceptorManager;
import com.team4u.framework.log.pipeline.interceptor.MdcEnrichInterceptor;
import com.team4u.framework.log.pipeline.interceptor.RateLimitInterceptor;
import com.team4u.framework.log.support.TestLogHelper;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.MDC;

public class EngineRuntimeIsolationTest {

    private LogEngine originalEngine;
    private TestLogHelper helper;

    @Before
    public void setUp() {
        originalEngine = LogEngine.getInstance();
        originalEngine.reset();
        helper = TestLogHelper.start();
    }

    @After
    public void tearDown() {
        LogEngine current = LogEngine.getInstance();
        if (current != originalEngine) {
            LogEngine.restore(current, originalEngine);
        }
        helper.stop();
        originalEngine.reset();
        MDC.clear();
    }

    @Test
    public void eachEngineOwnsAnIndependentRateInterceptor() {
        LogEngine first = LogEngine.builder().build();
        LogEngine second = LogEngine.builder().build();

        Assert.assertNotSame(rate(first), rate(second));

        rate(first).setErrorLimitPerSecond(() -> 0);
        Assert.assertEquals(0, currentLimit(first));
        Assert.assertEquals(RateLimitInterceptor.DEFAULT_ERROR_LIMIT_PER_SECOND, currentLimit(second));
    }

    @Test
    public void eachEngineOwnsAnIndependentMdcInterceptor() {
        LogEngine first = LogEngine.builder().build();
        LogEngine second = LogEngine.builder().build();

        Assert.assertNotSame(mdc(first), mdc(second));
        mdc(first).setTraceIdKey("requestId");

        MDC.put("traceId", "trace-value");
        MDC.put("requestId", "request-value");

        LogEvent firstEvent = new LogEvent();
        first.getInterceptorManager().execute(firstEvent);
        LogEvent secondEvent = new LogEvent();
        second.getInterceptorManager().execute(secondEvent);

        Assert.assertEquals("request-value", firstEvent.getTraceId());
        Assert.assertEquals("trace-value", secondEvent.getTraceId());
    }

    @Test
    public void engineResetDoesNotStopInjectedInterceptors() {
        RecordingInterceptor injected = new RecordingInterceptor();
        LogEngine engine = LogEngine.builder().interceptor(injected).build();

        engine.reset();

        Assert.assertFalse(injected.stopped);
        Assert.assertTrue(engine.getInterceptorManager().getInterceptors().contains(injected));
    }

    @Test
    public void independentlyResetEngineCannotChangeAnotherEngineRateState() {
        LogEngine governance = LogEngine.builder().build();
        LogEngine independent = LogEngine.builder().build();
        rate(governance).setErrorLimitPerSecond(() -> 1);
        Assert.assertTrue(rate(governance).handle(error("shared")));
        Assert.assertFalse(rate(governance).handle(error("shared")));

        rate(independent).setErrorLimitPerSecond(() -> 100);
        independent.reset();

        LogEvent next = error("shared");
        Assert.assertFalse(rate(governance).handle(next));
        Assert.assertTrue(next.isSuppressed());
    }

    private RateLimitInterceptor rate(LogEngine engine) {
        for (LogInterceptor interceptor : engine.getInterceptorManager().getInterceptors()) {
            if (interceptor instanceof RateLimitInterceptor) {
                return (RateLimitInterceptor) interceptor;
            }
        }
        throw new AssertionError("Rate interceptor not installed");
    }

    private MdcEnrichInterceptor mdc(LogEngine engine) {
        for (LogInterceptor interceptor : engine.getInterceptorManager().getInterceptors()) {
            if (interceptor instanceof MdcEnrichInterceptor) {
                return (MdcEnrichInterceptor) interceptor;
            }
        }
        throw new AssertionError("MDC interceptor not installed");
    }

    private int currentLimit(LogEngine engine) {
        int limit = 0;
        for (int i = 0; i < RateLimitInterceptor.DEFAULT_ERROR_LIMIT_PER_SECOND; i++) {
            LogEvent event = error("limit-probe-" + i);
            if (rate(engine).handle(event)) {
                limit++;
            } else {
                break;
            }
        }
        rate(engine).stop();
        return limit;
    }

    private LogEvent error(String action) {
        return new LogEvent().setAction(action).setException(new RuntimeException(action));
    }

    private static final class RecordingInterceptor implements LogInterceptor {
        private boolean stopped;

        @Override
        public boolean handle(LogEvent event) {
            return true;
        }

        @Override
        public void stop() {
            stopped = true;
        }
    }
}
