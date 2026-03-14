package com.team4u.framework.log.appender;

import com.team4u.framework.log.core.LogEvent;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.event.Level;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class CompositeLogAppenderTest {

    @Test
    public void testGetAppendersReturnsReadOnlyView() {
        CompositeLogAppender composite = new CompositeLogAppender(new NoopAppender());

        List<LogAppender> appenders = composite.getAppenders();
        try {
            appenders.add(new NoopAppender());
            Assert.fail("应返回只读视图");
        } catch (UnsupportedOperationException expected) {
            Assert.assertEquals(1, composite.getAppenders().size());
        }
    }

    @Test
    public void testConcurrentAppendAndAddAppender() throws Exception {
        CountingAppender countingAppender = new CountingAppender();
        CompositeLogAppender composite = new CompositeLogAppender(countingAppender);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(4);

        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 200; j++) {
                        composite.append(new LogEvent().setAction("A").setLevel(Level.INFO));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Assert.fail("线程被意外中断");
                } finally {
                    done.countDown();
                }
            });
        }

        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 50; j++) {
                        composite.addAppender(new NoopAppender());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Assert.fail("线程被意外中断");
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        Assert.assertTrue(done.await(2, TimeUnit.SECONDS));
        executor.shutdownNow();

        Assert.assertTrue(countingAppender.count.get() > 0);
        Assert.assertTrue(composite.removeAppender(countingAppender));
    }

    private static class NoopAppender implements LogAppender {
        @Override
        public void append(LogEvent event) {
        }
    }

    private static class CountingAppender implements LogAppender {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public void append(LogEvent event) {
            count.incrementAndGet();
        }
    }
}
