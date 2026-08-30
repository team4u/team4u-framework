package com.team4u.framework.config.core.internal;

import com.team4u.framework.config.core.domain.ConfigEntry;
import com.team4u.framework.config.core.domain.ConfigSnapshot;
import com.team4u.framework.config.core.spi.ConfigSource;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public class HotReloadManagerTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final long BLOCKING_CALLBACK_TIMEOUT_SECONDS = 15;
    private static final long CONCURRENT_PROGRESS_TIMEOUT_SECONDS = 2;

    private final TrackingSource source = new TrackingSource();
    private final Supplier<List<ConfigSource>> sources = () -> Collections.singletonList(source);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "hot-reload-test");
        thread.setDaemon(true);
        return thread;
    });

    private HotReloadManager manager;

    @After
    public void tearDown() throws Exception {
        if (manager != null) {
            manager.destroy();
        }
        executor.shutdownNow();
        Assert.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    public void queuedCancellationPreventsReloadBodyFromExecuting() throws Exception {
        manager = newManager(2_000, (generation, snapshot) -> {
            throw new AssertionError("A canceled reload must not commit");
        });

        source.put("key", "first");
        manager.signalChange();
        manager.cancelPendingReload();

        Assert.assertFalse("canceled reload must never execute its source body",
                source.loadStarted.await(200, TimeUnit.MILLISECONDS));
        Assert.assertEquals(0, source.startedLoads.get());
    }

    @Test
    public void resetThenResumeAcceptsANewSignal() throws Exception {
        AtomicInteger commits = new AtomicInteger();
        manager = newManager(2_000, (generation, snapshot) -> {
            commits.incrementAndGet();
            return new HotReloadManager.ReloadEvent(snapshot(11, "old"), snapshot);
        });

        source.put("key", "first");
        manager.signalChange();
        manager.cancelPendingReload();
        manager.resumeAcceptingReloads();
        manager.setDebounceWindowMs(50);

        source.put("key", "second");
        manager.signalChange();
        waitFor(() -> commits.get() == 1);

        Assert.assertEquals(1, source.startedLoads.get());
        Assert.assertEquals(1, commits.get());
    }

    @Test
    public void twoSequentialPositiveDelayReloadsBothCommit() throws Exception {
        AtomicInteger commits = new AtomicInteger();
        manager = newManager(50, (generation, snapshot) -> {
            commits.incrementAndGet();
            return new HotReloadManager.ReloadEvent(snapshot(11, "old"), snapshot);
        });

        source.put("key", "first");
        manager.signalChange();
        waitFor(() -> commits.get() == 1);

        source.put("key", "second");
        manager.signalChange();
        waitFor(() -> commits.get() == 2);

        Assert.assertEquals(2, source.startedLoads.get());
    }

    @Test
    public void laterPendingSignalCancelsEarlierQueuedReloadBody() throws Exception {
        AtomicInteger commits = new AtomicInteger();
        manager = newManager(2_000, (generation, snapshot) -> {
            commits.incrementAndGet();
            return new HotReloadManager.ReloadEvent(snapshot(11, "old"), snapshot);
        });

        source.put("key", "first");
        manager.signalChange();
        manager.setDebounceWindowMs(50);
        source.put("key", "second");
        manager.signalChange();

        waitFor(() -> commits.get() == 1);
        Assert.assertEquals("only the latest queued body may aggregate", 1, source.startedLoads.get());
        Assert.assertEquals("second", source.lastCommittedValue.get());
    }

    @Test
    public void newerSignalInvalidatesRunningReloadAndKeepsNewPendingHandle() throws Exception {
        AtomicInteger commits = new AtomicInteger();
        manager = newManager(0, (generation, snapshot) -> {
            commits.incrementAndGet();
            return new HotReloadManager.ReloadEvent(snapshot(11, "old"), snapshot);
        });

        source.put("key", "old");
        source.blockNextLoad.set(true);
        Future<?> oldReload = executor.submit(manager::signalChange);
        Assert.assertTrue(source.loadStarted.await(5, TimeUnit.SECONDS));

        manager.setDebounceWindowMs(2_000);
        source.put("key", "new");
        manager.signalChange();
        source.releaseLoad.countDown();
        oldReload.get(5, TimeUnit.SECONDS);

        Assert.assertEquals(1, source.startedLoads.get());
        Assert.assertEquals("stale running reload must not commit", 0, commits.get());

        Assert.assertNotNull("a stale task must not clear the newer pending handle",
                readPendingTask());
    }

    @Test
    public void committedReloadReturnsEventAndNotifiesOutsideExecutionLock() throws Exception {
        ConfigSnapshot oldSnapshot = snapshot(11, "old");
        AtomicReference<HotReloadManager.ReloadEvent> notifiedEvent = new AtomicReference<>();
        AtomicInteger notifications = new AtomicInteger();
        CountDownLatch firstNotifierEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstNotifier = new CountDownLatch(1);
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        manager = newManager(
                (generation, snapshot) -> new HotReloadManager.ReloadEvent(oldSnapshot, snapshot),
                event -> {
                    try {
                        int invocation = notifications.incrementAndGet();
                        if (invocation == 1) {
                            firstNotifierEntered.countDown();
                            awaitRelease(releaseFirstNotifier, BLOCKING_CALLBACK_TIMEOUT_SECONDS);
                        }
                        if (invocation == 2) {
                            notifiedEvent.set(event);
                        }
                    } catch (Throwable failure) {
                        callbackFailure.compareAndSet(null, failure);
                    }
                });

        source.put("key", "new");
        Future<?> firstReload = executor.submit(manager::signalChange);
        Assert.assertTrue(firstNotifierEntered.await(5, TimeUnit.SECONDS));

        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        Thread secondReload = new Thread(() -> {
            try {
                source.put("key", "second");
                manager.signalChange();
            } catch (Throwable failure) {
                secondFailure.set(failure);
            }
        }, "hot-reload-notifier-concurrency");
        secondReload.start();
        waitFor("second commit and callback must finish while the first callback is blocked",
                () -> source.startedLoads.get() == 2 && notifications.get() == 2,
                CONCURRENT_PROGRESS_TIMEOUT_SECONDS);

        releaseFirstNotifier.countDown();
        firstReload.get(5, TimeUnit.SECONDS);
        secondReload.join(TimeUnit.SECONDS.toMillis(5));
        Assert.assertFalse(secondReload.isAlive());
        Assert.assertNull(secondFailure.get());
        Assert.assertNull(callbackFailure.get());

        HotReloadManager.ReloadEvent event = notifiedEvent.get();
        Assert.assertNotNull(event);
        Assert.assertSame(oldSnapshot, event.oldSnapshot);
        Assert.assertEquals("second", event.newSnapshot.getEntries().get("key").getValue());
    }

    @Test
    public void staleReloadReturnsNoEventAndNeverNotifies() {
        AtomicBoolean notified = new AtomicBoolean();
        manager = newManager((generation, snapshot) -> null, event -> notified.set(true));

        source.put("key", "new");
        manager.signalChange();

        Assert.assertFalse(notified.get());
    }

    @Test
    public void notifierFailureDoesNotChangeCommittedSnapshot() {
        AtomicInteger notifications = new AtomicInteger();
        manager = newManager(
                (generation, snapshot) -> new HotReloadManager.ReloadEvent(snapshot(11, "old"), snapshot),
                event -> {
                    notifications.incrementAndGet();
                    throw new IllegalStateException("expected notifier failure");
                });

        source.put("key", "new");
        manager.signalChange();

        Assert.assertEquals(1, notifications.get());
    }

    private HotReloadManager newManager(ReloadCommitter committer, ReloadNotifier notifier) {
        return new HotReloadManager(
                sources,
                new SnapshotAggregator(),
                new AtomicLong(),
                0,
                committer,
                notifier);
    }

    private static ConfigSnapshot snapshot(long version, String value) {
        return new ConfigSnapshot(version, Collections.singletonMap(
                "key", new ConfigEntry("key", value, "tracking", 0)));
    }

    private static void awaitRelease(CountDownLatch latch, long timeoutSeconds) {
        try {
            if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "timed out after " + timeoutSeconds + "s waiting for latch release");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void waitFor(String message, BooleanSupplier condition, long timeoutSeconds)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError(message);
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
    }

    private HotReloadManager newManager(long delay, ReloadCommitter committer) {
        return new HotReloadManager(
                sources,
                new SnapshotAggregator(),
                new AtomicLong(),
                delay,
                committer,
                event -> {
                });
    }

    private Object readPendingTask() throws Exception {
        Field field = HotReloadManager.class.getDeclaredField("pendingReloadTask");
        field.setAccessible(true);
        return field.get(manager);
    }

    private static void waitFor(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("timed out waiting for condition");
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
    }

    private static final class TrackingSource implements ConfigSource {
        private final Map<String, ConfigEntry> data = Collections.synchronizedMap(new HashMap<>());
        private final AtomicInteger startedLoads = new AtomicInteger();
        private final AtomicReference<String> lastCommittedValue = new AtomicReference<>();
        private final AtomicBoolean blockNextLoad = new AtomicBoolean();
        private final CountDownLatch loadStarted = new CountDownLatch(1);
        private final CountDownLatch releaseLoad = new CountDownLatch(1);

        void put(String key, String value) {
            data.put(key, new ConfigEntry(key, value, "tracking", 0));
        }

        @Override
        public String name() {
            return "tracking";
        }

        @Override
        public Map<String, ConfigEntry> load() {
            startedLoads.incrementAndGet();
            loadStarted.countDown();
            if (blockNextLoad.get()) {
                awaitRelease();
            }
            Map<String, ConfigEntry> snapshot = new HashMap<>(data);
            lastCommittedValue.set(snapshot.get("key").getValue());
            return snapshot;
        }

        @Override
        public int priority() {
            return 1;
        }

        private void awaitRelease() {
            try {
                if (!releaseLoad.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting for source release");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }
}
