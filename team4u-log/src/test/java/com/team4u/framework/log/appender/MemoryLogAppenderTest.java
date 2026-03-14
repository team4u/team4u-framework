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

public class MemoryLogAppenderTest {

    @Test
    public void testConcurrentAppendStaysWithinCapacity() throws Exception {
        MemoryLogAppender appender = new MemoryLogAppender();
        appender.setCapacity(20);

        ExecutorService executor = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(6);

        for (int i = 0; i < 6; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 50; j++) {
                        appender.append(new LogEvent()
                                .setAction("t" + threadId + "-" + j)
                                .setLevel(Level.INFO));
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

        Assert.assertTrue(appender.size() <= 20);
        Assert.assertNotNull(appender.lastEvent());
    }

    @Test
    public void testShrinkCapacityTrimsOldEvents() {
        MemoryLogAppender appender = new MemoryLogAppender();
        for (int i = 0; i < 5; i++) {
            appender.append(new LogEvent().setAction("A" + i).setLevel(Level.INFO));
        }

        appender.setCapacity(2);

        List<LogEvent> events = appender.getEvents();
        Assert.assertEquals(2, events.size());
        Assert.assertEquals("A3", events.get(0).getAction());
        Assert.assertEquals("A4", events.get(1).getAction());
    }
}
