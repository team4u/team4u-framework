package com.team4u.framework.log.core;

import com.team4u.framework.log.appender.LogAppender;
import com.team4u.framework.log.appender.Slf4jLogAppender;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class EngineAppenderLockingTest {

    private static final long TIMEOUT_SECONDS = 10;

    private static LogEngine original;
    private static LogAppender originalAppender;

    @BeforeClass
    public static void saveGlobalEngine() {
        original = LogEngine.getInstance();
        originalAppender = original.getAppender();
    }

    @AfterClass
    public static void restoreGlobalEngine() {
        LogEngine current = LogEngine.getInstance();
        if (current != original) {
            LogEngine.restore(current, original);
        }
        LogEngine.setGlobalAppender(originalAppender);
    }

    @Test
    public void detachedEngineAppenderWriteDoesNotWaitForGlobalTransform() throws Exception {
        CountDownLatch transformStarted = new CountDownLatch(1);
        CountDownLatch releaseTransform = new CountDownLatch(1);
        CountDownLatch detachedWriteStarted = new CountDownLatch(1);
        CountDownLatch detachedWriteFinished = new CountDownLatch(1);
        LogEngine detached = LogEngine.builder().build();
        LogAppender detachedAppender = new Slf4jLogAppender();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<LogAppender> transformFuture = executor.submit(() ->
                    LogEngine.updateGlobalAppender(current -> {
                        transformStarted.countDown();
                        await(releaseTransform);
                        return current;
                    }));
            Assert.assertTrue("global transform did not start", transformStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            Assert.assertTrue(transformStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            Future<?> detachedWriteFuture = executor.submit(() -> {
                detachedWriteStarted.countDown();
                detached.setAppender(detachedAppender);
                detachedWriteFinished.countDown();
            });
            Assert.assertTrue("detached appender write did not start",
                    detachedWriteStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            try {
                detachedWriteFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException error) {
                throw new AssertionError(
                        "detached appender write waited for an unrelated blocked global transform", error);
            }
            Assert.assertSame(detachedAppender, detached.getAppender());
        } finally {
            releaseTransform.countDown();
            executor.shutdownNow();
            Assert.assertTrue(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            Assert.assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for latch", error);
        }
    }
}
