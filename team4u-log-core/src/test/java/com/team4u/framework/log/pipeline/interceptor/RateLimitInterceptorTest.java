package com.team4u.framework.log.pipeline.interceptor;

import com.team4u.framework.log.core.LogEvent;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class RateLimitInterceptorTest {

    private RateLimitInterceptor interceptor;
    private volatile int limit = 2;

    @Before
    public void setup() {
        interceptor = RateLimitInterceptor.getInstance();
        interceptor.stop();
        interceptor.setErrorLimitPerSecond(() -> limit);
    }

    @Test
    public void testRateLimiting() {
        LogEvent event1 = createErrorEvent("ActionA", new RuntimeException("E1"));
        LogEvent event2 = createErrorEvent("ActionA", new RuntimeException("E1"));
        LogEvent event3 = createErrorEvent("ActionA", new RuntimeException("E1"));

        Assert.assertTrue(interceptor.handle(event1));
        Assert.assertFalse(event1.isSuppressed());
        Assert.assertTrue(interceptor.handle(event2));
        Assert.assertFalse(event2.isSuppressed());
        Assert.assertFalse(interceptor.handle(event3));
        Assert.assertTrue(event3.isSuppressed());
    }

    @Test
    public void testDifferentExceptions() {
        Assert.assertTrue(interceptor.handle(createErrorEvent("ActionA", new RuntimeException("E1"))));
        Assert.assertTrue(interceptor.handle(createErrorEvent("ActionA", new IllegalArgumentException("E2"))));
    }

    @Test
    public void testDifferentLoggerNamesUseDifferentBuckets() {
        limit = 1;
        Assert.assertTrue(interceptor.handle(createErrorEvent("ActionA", new RuntimeException("E1")).setLoggerName("service.A")));
        Assert.assertFalse(interceptor.handle(createErrorEvent("ActionA", new RuntimeException("E1")).setLoggerName("service.A")));
        Assert.assertTrue(interceptor.handle(createErrorEvent("ActionA", new RuntimeException("E1")).setLoggerName("service.B")));
    }

    @Test
    public void testNonErrorEvent() {
        Assert.assertTrue(interceptor.handle(new LogEvent().setAction("ActionA")));
    }

    @Test
    public void testResetAndThresholdUpdate() {
        limit = 1;
        interceptor.handle(createErrorEvent("X", new RuntimeException()));
        Assert.assertFalse(interceptor.handle(createErrorEvent("X", new RuntimeException())));

        limit = 10;
        interceptor.stop();
        Assert.assertTrue(interceptor.handle(createErrorEvent("X", new RuntimeException())));
    }

    @Test
    public void defaultThresholdIsTenAndSupplierCanBeReset() {
        interceptor.resetErrorLimitPerSecond();
        limit = 0;
        interceptor.stop();

        for (int i = 0; i < 10; i++) {
            Assert.assertTrue(interceptor.handle(createErrorEvent("default-" + i, new RuntimeException("E1"))));
        }

        LogEvent overflow = createErrorEvent("default-overflow", new RuntimeException("E1"));
        Assert.assertTrue(interceptor.handle(overflow));
        Assert.assertFalse(overflow.isSuppressed());
    }

    @Test
    public void testPriority() {
        Assert.assertEquals(1000, interceptor.priority());
    }

    @Test
    public void testConcurrentSameSignature() throws Exception {
        limit = 100;
        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    Assert.assertTrue(interceptor.handle(createErrorEvent("ActionC", new RuntimeException("E1"))));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Assert.fail("thread interrupted");
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        Assert.assertTrue(done.await(2, TimeUnit.SECONDS));
        executor.shutdown();

        LogEvent overflow = createErrorEvent("ActionC", new RuntimeException("E1"));
        Assert.assertTrue(interceptor.handle(overflow));
        Assert.assertFalse(overflow.isSuppressed());
    }
    private LogEvent createErrorEvent(String action, Throwable e) {
        return new LogEvent().setAction(action).setException(e);
    }
}
