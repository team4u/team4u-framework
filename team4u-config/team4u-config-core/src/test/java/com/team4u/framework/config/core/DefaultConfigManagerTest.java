package com.team4u.framework.config.core;

import com.team4u.framework.base.util.ThreadUtil;
import com.team4u.framework.config.core.convert.PropertyConverterRegistry;
import com.team4u.framework.config.core.domain.ConfigEntry;
import com.team4u.framework.config.core.domain.ConfigSnapshot;
import com.team4u.framework.config.core.internal.DefaultConfigManager;
import com.team4u.framework.config.core.proxy.ConfigProxyFactory;
import com.team4u.framework.config.core.spi.*;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class DefaultConfigManagerTest {

    @Test
    public void testManagerLifecycleAndDebounce() {
        AtomicInteger loadCount = new AtomicInteger();
        Map<String, ConfigEntry> initialData = new HashMap<>();
        initialData.put("key1", new ConfigEntry("key1", "val1", "mock", 0));

        MockSource source = new MockSource("MockSource", 1, loadCount, initialData);
        MockWatcher watcher = new MockWatcher();

        // 模拟绑定器以使用代理工厂执行绑定
        ConfigBinder binder = new ConfigBinder() {
            private final ConfigProxyFactory factory = new ConfigProxyFactory(new PropertyConverterRegistry());

            @Override
            public <T> T bind(ConfigSnapshot snapshot, String prefix, Class<T> type) {
                // 这里的简单测试可以跳过或返回 null
                return null;
            }
        };

        ConfigSourceRegistry sourceRegistry = new ConfigSourceRegistry();
        sourceRegistry.register(source);

        ConfigWatcherRegistry watcherRegistry = new ConfigWatcherRegistry();
        watcherRegistry.register(watcher);

        DefaultConfigManager manager = new DefaultConfigManager(
                sourceRegistry,
                watcherRegistry,
                new PropertyConverterRegistry(),
                binder,
                500);

        Assert.assertEquals(1, watcher.initCount.get());
        Assert.assertEquals(1, watcher.watchCount.get());

        // 验证首次同步加载后的数据状态
        Assert.assertEquals(1, loadCount.get());
        Assert.assertEquals("val1", manager.getString("key1").orElse(null));
        long initialVersion = manager.currentSnapshot().getVersion();

        // 注册监听器来测试变更分发
        AtomicInteger changeEvtCount = new AtomicInteger();
        manager.registerChangeListener("key1", (key, oldVal, newVal) -> {
            changeEvtCount.incrementAndGet();
            Assert.assertEquals("val1", oldVal);
            Assert.assertEquals("val2", newVal);
        });

        manager.registerChangeListener("app.*", (key, oldVal, newVal) -> {
            changeEvtCount.incrementAndGet();
            Assert.assertEquals("app.name", key);
            Assert.assertNull(oldVal);
            Assert.assertEquals("newApp", newVal);
        });

        // 模拟触发频繁变更信号 (验证防抖拦截功)
        source.data.put("key1", new ConfigEntry("key1", "val2", "mock", 1));
        source.data.put("app.name", new ConfigEntry("app.name", "newApp", "mock", 1));

        watcher.trigger();
        watcher.trigger();
        watcher.trigger();

        // 信号刚发出时，加载次数应保持不变 (防抖窗口由于异步未结束)
        Assert.assertEquals(1, loadCount.get());
        Assert.assertEquals(0, changeEvtCount.get());

        // 等待防抖窗口结束 (500ms + 额外缓冲时间)
        ThreadUtil.sleep(800);

        // 防抖合并结束后，应仅额外触发一次加载行为
        Assert.assertEquals(2, loadCount.get());
        Assert.assertEquals("val2", manager.getString("key1").orElse(null));
        Assert.assertTrue(manager.currentSnapshot().getVersion() > initialVersion);

        // 验证监听器被准确触发了 2 次 (key1 + app.name)
        Assert.assertEquals(2, changeEvtCount.get());

        manager.refresh();
        Assert.assertEquals("refresh 不应重复初始化 watcher", 1, watcher.initCount.get());
        Assert.assertEquals("refresh 不应重复注册 watcher", 1, watcher.watchCount.get());

        manager.destroy();
        Assert.assertEquals(1, watcher.destroyCount.get());
    }

    @Test
    public void testRegisterChangeListenerCanBeClosed() throws Exception {
        AtomicInteger loadCount = new AtomicInteger();
        Map<String, ConfigEntry> initialData = new HashMap<>();
        initialData.put("key1", new ConfigEntry("key1", "val1", "mock", 0));

        MockSource source = new MockSource("MockSource", 1, loadCount, initialData);
        MockWatcher watcher = new MockWatcher();

        ConfigSourceRegistry sourceRegistry = new ConfigSourceRegistry();
        sourceRegistry.register(source);

        ConfigWatcherRegistry watcherRegistry = new ConfigWatcherRegistry();
        watcherRegistry.register(watcher);

        DefaultConfigManager manager = new DefaultConfigManager(
                sourceRegistry,
                watcherRegistry,
                new PropertyConverterRegistry(),
                new ConfigBinder() {
                    @Override
                    public <T> T bind(ConfigSnapshot snapshot, String prefix, Class<T> type) {
                        return null;
                    }
                },
                0);

        AtomicReference<String> seenValue = new AtomicReference<>();
        AutoCloseable handle = manager.registerChangeListener("key1", (key, oldVal, newVal) -> seenValue.set(newVal));

        source.data.put("key1", new ConfigEntry("key1", "val2", "mock", 1));
        watcher.trigger();
        Assert.assertEquals("val2", seenValue.get());

        handle.close();
        seenValue.set(null);

        source.data.put("key1", new ConfigEntry("key1", "val3", "mock", 2));
        watcher.trigger();
        Assert.assertNull(seenValue.get());

        manager.destroy();
    }

    private static class MockSource implements ConfigSource {
        public final Map<String, ConfigEntry> data;
        private final String name;
        private final int priority;
        private final AtomicInteger loadCount;

        MockSource(String name, int priority, AtomicInteger loadCount, Map<String, ConfigEntry> data) {
            this.name = name;
            this.priority = priority;
            this.loadCount = loadCount;
            this.data = data;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Map<String, ConfigEntry> load() {
            loadCount.incrementAndGet();
            return new HashMap<>(data);
        }

        @Override
        public int priority() {
            return priority;
        }
    }

    private static class MockWatcher implements ConfigWatcher {
        private final AtomicInteger initCount = new AtomicInteger();
        private final AtomicInteger watchCount = new AtomicInteger();
        private final AtomicInteger destroyCount = new AtomicInteger();
        private Runnable changeSignal;

        @Override
        public void init() {
            initCount.incrementAndGet();
        }

        @Override
        public void watch(Runnable changeSignal) {
            watchCount.incrementAndGet();
            this.changeSignal = changeSignal;
        }

        @Override
        public void destroy() {
            destroyCount.incrementAndGet();
        }

        public void trigger() {
            if (changeSignal != null) {
                changeSignal.run();
            }
        }
    }
}
