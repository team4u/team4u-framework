package com.team4u.framework.config.core;

import com.team4u.framework.config.core.internal.DefaultConfigManager;
import com.team4u.framework.config.core.spi.ConfigSourceRegistry;
import com.team4u.framework.config.core.spi.ConfigWatcherRegistry;
import com.team4u.framework.config.core.spi.InMemoryConfigSource;
import com.team4u.framework.config.core.convert.PropertyConverterRegistry;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class ConfigGlobalInitializationTest {

    @Before
    @After
    public void resetGlobalState() {
        ConfigBootstrap.global().resetForTests();
    }

    @Test
    public void bootstrapRefreshesExistingGlobalWithoutReplacingIt() {
        ConfigManager first = ConfigManager.global();

        InMemoryConfigSource source = new InMemoryConfigSource("bootstrap-test", 1);
        source.put("bootstrap.key", "value");
        ConfigBootstrap.global()
                .addSource(source)
                .lock();

        Assert.assertSame(first, ConfigManager.global());
        Assert.assertEquals("value", first.getString("bootstrap.key").orElse(null));
        Assert.assertEquals("value", ConfigManager.global().getString("bootstrap.key").orElse(null));
    }

    @Test
    public void resetDoesNotInitializeAbsentGlobal() {
        // resetForTests() must leave an absent global absent. The first real global()
        // initialization below must therefore load the source exactly once.
        ConfigBootstrap.global().resetForTests();

        LoadCountSource source = new LoadCountSource("after-reset", 1);
        ConfigBootstrap.global().addSource(source);
        ConfigManager.global();

        Assert.assertEquals(1, source.loadCount.get());
    }

    @Test
    public void resetDestroysActiveWatcherAndKeepsRuntimeReusable() {
        ConfigManager.global();

        LifecycleWatcher watcher = new LifecycleWatcher();
        ConfigBootstrap.global().addWatcher(watcher);
        DefaultConfigManager.global().refresh();

        Assert.assertEquals(1, watcher.initCount.get());
        Assert.assertEquals(1, watcher.watchCount.get());

        ConfigBootstrap.global().resetForTests();

        Assert.assertEquals("reset must not re-initialize the removed watcher",
                1, watcher.initCount.get());
        Assert.assertEquals("reset must not re-watch the removed watcher",
                1, watcher.watchCount.get());
        Assert.assertEquals(1, watcher.destroyCount.get());
        Assert.assertTrue(ConfigSourceRegistry.global().getPolicies().isEmpty());
        Assert.assertTrue(ConfigWatcherRegistry.global().getPolicies().isEmpty());
        Assert.assertTrue(PropertyConverterRegistry.global().getPolicies().isEmpty());
        Assert.assertTrue(DefaultConfigManager.global().currentSnapshot().getEntries().isEmpty());

        InMemoryConfigSource source = new InMemoryConfigSource("reusable-after-reset", 1);
        source.put("bootstrap.key", "value");
        ConfigBootstrap.global().addSource(source).lock();
        DefaultConfigManager.refreshGlobalIfInitialized();

        Assert.assertEquals("value", ConfigManager.global().getString("bootstrap.key").orElse(null));
    }

    private static class LoadCountSource extends InMemoryConfigSource {
        private final AtomicInteger loadCount = new AtomicInteger();

        private LoadCountSource(String name, int priority) {
            super(name, priority);
        }

        @Override
        public java.util.Map<String, com.team4u.framework.config.core.domain.ConfigEntry> load() {
            loadCount.incrementAndGet();
            return super.load();
        }
    }

    private static class LifecycleWatcher extends InMemoryConfigSource {
        private final AtomicInteger initCount = new AtomicInteger();
        private final AtomicInteger watchCount = new AtomicInteger();
        private final AtomicInteger destroyCount = new AtomicInteger();

        private LifecycleWatcher() {
            super("lifecycle-watcher", 1);
        }

        @Override
        public void init() {
            initCount.incrementAndGet();
        }

        @Override
        public void watch(Runnable changeSignal) {
            watchCount.incrementAndGet();
            super.watch(changeSignal);
        }

        @Override
        public void destroy() {
            destroyCount.incrementAndGet();
        }
    }
}
