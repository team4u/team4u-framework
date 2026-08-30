package com.team4u.framework.log.core;

import com.team4u.framework.log.appender.LogAppender;
import com.team4u.framework.log.appender.SerializerAwareLogAppender;
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
import java.util.concurrent.atomic.AtomicInteger;
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

            Future<?> detachedWriteFuture = executor.submit(() -> {
                detachedWriteStarted.countDown();
                detached.setAppender(detachedAppender);
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

    @Test
    public void currentOwnerWriteDuringInstallTransfersAndThenBecomesDetached() throws Exception {
        LogEngine source = LogEngine.getInstance();
        LogEngine target = LogEngine.builder().build();
        RecordingAppender newer = new RecordingAppender();
        CountDownLatch installRequested = new CountDownLatch(1);
        CountDownLatch targetBindingStarted = new CountDownLatch(1);
        CountDownLatch releaseInstall = new CountDownLatch(1);
        CountDownLatch detachedWriteStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        BlockingAppender transferred = new BlockingAppender(targetBindingStarted, releaseInstall);

        source.setAppender(transferred);

        try {
            Future<?> installFuture = executor.submit(() -> {
                installRequested.countDown();
                LogEngine.install(target);
                return null;
            });
            Assert.assertTrue("install did not start",
                    installRequested.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            Assert.assertTrue("target serializer binding did not start after source snapshot",
                    targetBindingStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            Assert.assertTrue("install must be blocked in target serializer binding", transferred.blocked);

            Future<?> currentOwnerWriteFuture = executor.submit(() -> {
                detachedWriteStarted.countDown();
                source.setAppender(newer);
                return null;
            });
            Assert.assertTrue("current-owner appender write did not start",
                    detachedWriteStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            try {
                currentOwnerWriteFuture.get(200, TimeUnit.MILLISECONDS);
                throw new AssertionError("current-owner appender write completed during global transfer");
            } catch (TimeoutException expected) {
                // The write must stay serialized with ownership transfer.
            }

            releaseInstall.countDown();
            installFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            currentOwnerWriteFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            Assert.assertSame(target, LogEngine.getInstance());
            Assert.assertSame(transferred, target.getAppender());
            Assert.assertSame(target.getSerializer(), transferred.serializer);
            Assert.assertSame(newer, source.getAppender());
            Assert.assertSame(source.getSerializer(), newer.serializer);

            Assert.assertTrue(LogEngine.restore(target, source));
            Assert.assertSame(source, LogEngine.getInstance());
            Assert.assertSame(transferred, source.getAppender());
        } finally {
            releaseInstall.countDown();
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

    private static final class BlockingAppender implements SerializerAwareLogAppender {
        private final CountDownLatch bindingStarted;
        private final CountDownLatch releaseBinding;
        private final AtomicInteger bindings = new AtomicInteger();
        private volatile boolean blocked;
        private volatile LogSerializer serializer;

        private BlockingAppender(CountDownLatch bindingStarted, CountDownLatch releaseBinding) {
            this.bindingStarted = bindingStarted;
            this.releaseBinding = releaseBinding;
        }
        @Override
        public void append(LogEvent event) {
        }

        @Override
        public void bindSerializer(LogSerializer serializer) {
            this.serializer = serializer;
            if (bindings.incrementAndGet() == 2) {
                blocked = true;
                bindingStarted.countDown();
                await(releaseBinding);
                blocked = false;
            }
        }
    }

    private static final class RecordingAppender implements SerializerAwareLogAppender {
        private volatile LogSerializer serializer;

        @Override
        public void append(LogEvent event) {
        }

        @Override
        public void bindSerializer(LogSerializer serializer) {
            this.serializer = serializer;
        }
    }
}
