package com.team4u.framework.config.core;

import com.team4u.framework.config.core.internal.DefaultConfigManager;
import com.team4u.framework.config.core.spi.ConfigSource;
import com.team4u.framework.config.core.spi.ConfigSourceRegistry;
import com.team4u.framework.config.core.spi.ConfigWatcherRegistry;
import com.team4u.framework.config.core.convert.PropertyConverterRegistry;
import com.team4u.framework.config.core.spi.InMemoryConfigSource;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

public class ConfigGlobalLifecycleConcurrencyTest {

    private static final long TIMEOUT_SECONDS = 5;

    private ExecutorService executor;

    @Before
    public void setUp() {
        executor = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "config-lifecycle-test");
            thread.setDaemon(true);
            return thread;
        });
        discardAndResetGlobal();
    }

    @After
    public void tearDown() {
        discardAndResetGlobal();
        executor.shutdownNow();
    }

    private void discardAndResetGlobal() {
        ConfigBootstrap.global().resetForTests();
        DefaultConfigManager.discardGlobalForTests();
    }

    @Test
    public void resetOfAbsentGlobalDoesNotInitializeIt() throws Exception {
        Assert.assertNull(DefaultConfigManager.globalOrNullForTests());

        ConfigBootstrap.global().resetForTests();

        Assert.assertNull(DefaultConfigManager.globalOrNullForTests());

        BlockingSource source = new BlockingSource("absent-reset", false, new CountDownLatch(1));
        source.put("absent.key", "value");
        ConfigBootstrap.global().addSource(source);

        ConfigManager.global();

        Assert.assertEquals(1, source.loadCount.get());
        Assert.assertNotNull(DefaultConfigManager.globalOrNullForTests());
    }

    @Test
    public void lateRegistrationWaitsForInitializationAndRefreshesSameGlobal() throws Exception {
        CountDownLatch releaseLoad = new CountDownLatch(1);
        BlockingSource source = new BlockingSource("late-registration-init", true, releaseLoad);
        source.put("initial.key", "initial");
        ConfigBootstrap.global().addSource(source);

        Future<ConfigManager> initialization = executor.submit(ConfigManager::global);
        Assert.assertTrue(source.loadEntered.await(5, TimeUnit.SECONDS));

        InMemoryConfigSource lateSource = new InMemoryConfigSource("late-registration-source", 2);
        lateSource.put("late.key", "late");
        Thread registration = new Thread(() -> ConfigBootstrap.global().addSource(lateSource),
                "config-late-registration");
        registration.start();

        waitFor("late source must be registered before refresh queues",
                () -> ConfigSourceRegistry.global().getPolicies().contains(lateSource));
        waitFor("registration refresh must block on the global monitor",
                () -> registration.getState() == Thread.State.BLOCKED
                        && isWaitingAt(registration, "refreshGlobalIfInitialized"));
        Assert.assertTrue(registration.isAlive());

        releaseLoad.countDown();
        ConfigManager initialized = initialization.get(5, TimeUnit.SECONDS);
        registration.join(TimeUnit.SECONDS.toMillis(5));
        Assert.assertFalse(registration.isAlive());

        Assert.assertSame(initialized, ConfigManager.global());
        Assert.assertEquals("late", initialized.getString("late.key").orElse(null));
    }

    @Test
    public void normalResetPreservesGlobalInstance() {
        ConfigManager first = ConfigManager.global();

        ConfigBootstrap.global().resetForTests();

        Assert.assertSame(first, ConfigManager.global());
    }

    @Test
    public void resetWaitsForConcurrentInitializationAndClearsItsResult() throws Exception {
        CountDownLatch releaseLoad = new CountDownLatch(1);
        BlockingSource source = new BlockingSource("concurrent-init", true, releaseLoad);
        source.put("concurrent.key", "old");
        ConfigBootstrap.global().addSource(source);

        Future<ConfigManager> initialization = executor.submit(ConfigManager::global);
        Assert.assertTrue(source.loadEntered.await(5, TimeUnit.SECONDS));

        Future<?> reset = executor.submit(() -> ConfigBootstrap.global().resetForTests());
        waitFor("reset must serialize with in-flight global initialization", () -> !reset.isDone());

        releaseLoad.countDown();
        ConfigManager initialized = initialization.get(5, TimeUnit.SECONDS);
        reset.get(5, TimeUnit.SECONDS);

        Assert.assertSame(initialized, ConfigManager.global());

        Assert.assertTrue(initialized.currentSnapshot().getEntries().isEmpty());
        Assert.assertTrue(ConfigSourceRegistry.global().getPolicies().isEmpty());
    }

    @Test
    public void resetInvalidatesInFlightReloadAndRefreshResumesAcceptance() throws Exception {
        CountDownLatch releaseLoad = new CountDownLatch(1);
        BlockingSource source = new BlockingSource("in-flight-reload", false, releaseLoad);
        source.put("hot.key", "old");
        RecordingWatcher watcher = new RecordingWatcher();
        ConfigBootstrap.global().addSource(source).addWatcher(watcher);
        DefaultConfigManager manager = DefaultConfigManager.global();
        AtomicBoolean staleEvent = new AtomicBoolean(false);
        manager.registerChangeListener("hot.key", (key, oldValue, newValue) -> staleEvent.set(true));
        source.blockReload.set(true);
        Future<?> reload = executor.submit(watcher::trigger);
        Assert.assertTrue(source.loadEntered.await(5, TimeUnit.SECONDS));

        Future<?> reset = executor.submit(() -> ConfigBootstrap.global().resetForTests());
        Assert.assertTrue("reset must complete while an invalidated reload is still aggregating",
                reset.get(1, TimeUnit.SECONDS) == null);
        Assert.assertEquals(1, watcher.destroyCount.get());

        releaseLoad.countDown();
        reload.get(5, TimeUnit.SECONDS);

        Assert.assertTrue(manager.currentSnapshot().getEntries().isEmpty());
        Assert.assertFalse("stale reload must not fire listeners", staleEvent.get());

        source.put("hot.key", "refreshed");
        source.blockReload.set(false);
        ConfigBootstrap.global().addSource(source).addWatcher(watcher);
        Assert.assertEquals("refreshed", ConfigManager.global().getString("hot.key").orElse(null));

        manager.setDebounceWindowMs(0);
        source.put("hot.key", "resumed");
        watcher.trigger();

        Assert.assertEquals("resumed", ConfigManager.global().getString("hot.key").orElse(null));
        Assert.assertEquals(1, watcher.destroyCount.get());
    }

    @Test
    public void resetCancelsQueuedReload() throws Exception {
        InMemoryConfigSource source = new InMemoryConfigSource("queued-reload", 1);
        source.put("queued.key", "old");
        ConfigBootstrap.global().addSource(source);
        DefaultConfigManager manager = DefaultConfigManager.global();
        manager.setDebounceWindowMs(50);
        AtomicReference<Runnable> signal = new AtomicReference<>();
        RecordingWatcher watcher = new RecordingWatcher(signal);
        ConfigBootstrap.global().addWatcher(watcher);

        source.put("queued.key", "new");
        signal.get().run();

        ConfigBootstrap.global().resetForTests();

        Assert.assertTrue(manager.currentSnapshot().getEntries().isEmpty());
        Assert.assertEquals(1, watcher.destroyCount.get());
    }

    @Test
    public void synchronousReloadListenerCanResetManagerWithoutDeadlock() throws Exception {
        InMemoryConfigSource source = new InMemoryConfigSource("listener-reset", 1);
        source.put("listener.key", "old");
        ConfigSourceRegistry sources = new ConfigSourceRegistry();
        ConfigWatcherRegistry watchers = new ConfigWatcherRegistry();
        sources.register(source);
        AtomicReference<Runnable> signalReference = new AtomicReference<>();
        RecordingWatcher watcher = new RecordingWatcher(signalReference);
        watchers.register(watcher);

        DefaultConfigManager manager = new DefaultConfigManager(
                sources, watchers, new PropertyConverterRegistry(), null, 0);
        manager.registerChangeListener("listener.key", (key, oldValue, newValue) -> {
            try {
                manager.resetForTests();
            } catch (RuntimeException e) {
                throw e;
            }
        });

        source.put("listener.key", "new");
        Future<?> reload = executor.submit(signalReference.get()::run);

        reload.get(2, TimeUnit.SECONDS);
        Assert.assertTrue(manager.currentSnapshot().getEntries().isEmpty());
        Assert.assertEquals(1, watcher.destroyCount.get());
    }

    private static void waitFor(String message, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError(message);
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
    }

    private static boolean isWaitingAt(Thread thread, String methodName) {
        StackTraceElement[] stack = thread.getStackTrace();
        return stack.length > 0 && methodName.equals(stack[0].getMethodName());
    }

    private static void awaitUnchecked(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for latch release");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static final class BlockingSource extends InMemoryConfigSource {
        private final AtomicBoolean blockFirstLoad;
        private final AtomicBoolean blockReload = new AtomicBoolean();
        private final CountDownLatch loadEntered = new CountDownLatch(1);
        private CountDownLatch releaseLoad;
        private final AtomicInteger loadCount = new AtomicInteger();
        private volatile boolean firstLoad = true;

        private BlockingSource(String name, boolean blockFirstLoad, CountDownLatch releaseLoad) {
            super(name, 1);
            this.blockFirstLoad = new AtomicBoolean(blockFirstLoad);
            this.releaseLoad = releaseLoad;
        }

        @Override
        public Map<String, com.team4u.framework.config.core.domain.ConfigEntry> load() {
            int count = loadCount.incrementAndGet();
            boolean shouldBlock = (firstLoad && blockFirstLoad.get()) || (!firstLoad && blockReload.get());
            firstLoad = false;
            if (shouldBlock && count <= 2) {
                loadEntered.countDown();
                try {
                    if (!releaseLoad.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting for load release");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return super.load();
        }
    }

    private static final class RecordingWatcher extends InMemoryConfigSource {
        private final AtomicInteger initCount = new AtomicInteger();
        private final AtomicInteger watchCount = new AtomicInteger();
        private final AtomicInteger destroyCount = new AtomicInteger();
        private final AtomicReference<Runnable> signalRef;

        private RecordingWatcher() {
            this(new AtomicReference<>());
        }

        private RecordingWatcher(AtomicReference<Runnable> signalRef) {
            super("recording-watcher", 1);
            this.signalRef = signalRef;
        }

        @Override
        public void init() {
            initCount.incrementAndGet();
        }

        @Override
        public void watch(Runnable changeSignal) {
            watchCount.incrementAndGet();
            signalRef.set(changeSignal);
        }

        @Override
        public void destroy() {
            destroyCount.incrementAndGet();
        }

        private void trigger() {
            Runnable signal = signalRef.get();
            if (signal != null) {
                signal.run();
            }
        }
    }
}
