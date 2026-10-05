package com.team4u.framework.config.core.support;

import com.team4u.framework.base.util.MapReader;
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.spi.InMemoryConfigSource;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ConfigDrivenRegistry 并发硬化与可观测性专项测试
 */
public class ConfigDrivenRegistryConcurrencyTest {

    private InMemoryConfigSource configSource;
    private ConfigManager configManager;

    @Before
    public void setUp() {
        configSource = new InMemoryConfigSource("test", 100);
        configManager = ConfigManager.builder()
                .addSource(configSource)
                .addWatcher(configSource)
                .debounceWindow(0)
                .build();
    }

    /**
     * 两线程以 CountDownLatch 同时进入构建路径：
     * 断言工厂只执行一次、close 计数与实例存活数一致（无泄漏）
     */
    @Test
    public void testConcurrentLazyInitSingleFactoryExecution() throws Exception {
        AtomicInteger factoryCallCount = new AtomicInteger(0);
        AtomicInteger closeCount = new AtomicInteger(0);

        CountDownLatch enterFactoryLatch = new CountDownLatch(1);
        CountDownLatch releaseFactoryLatch = new CountDownLatch(1);

        ConfigDrivenRegistry<ClosableResource> registry = ConfigDrivenRegistry.forKeys(
                configManager, "test.*", (key, val) -> {
                    factoryCallCount.incrementAndGet();
                    enterFactoryLatch.countDown();
                    try {
                        releaseFactoryLatch.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return new ClosableResource(val, closeCount);
                });

        configSource.putAndRefresh("test.k1", "v1");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ClosableResource> f1 = executor.submit(() -> registry.get("k1"));
            Assert.assertTrue("Factory should be entered by first thread",
                    enterFactoryLatch.await(5, TimeUnit.SECONDS));

            Future<ClosableResource> f2 = executor.submit(() -> registry.get("k1"));

            releaseFactoryLatch.countDown();

            ClosableResource r1 = f1.get(5, TimeUnit.SECONDS);
            ClosableResource r2 = f2.get(5, TimeUnit.SECONDS);

            Assert.assertNotNull(r1);
            Assert.assertSame("Both threads must receive the same instance", r1, r2);
            Assert.assertEquals("Factory must be executed exactly once", 1, factoryCallCount.get());
            Assert.assertEquals("No instance should be closed", 0, closeCount.get());
            Assert.assertFalse("Instance must remain open", r1.isClosed());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 惰性构建与热重载回调竞态：
     * 业务线程在构建中时触发热重载，必须串行化构建且旧实例被正常关闭，绝不泄漏
     */
    @Test
    public void testConcurrentLazyInitAndHotReloadNoLeak() throws Exception {
        AtomicInteger factoryCallCount = new AtomicInteger(0);
        AtomicInteger closeCount = new AtomicInteger(0);

        CountDownLatch firstBuildEnterLatch = new CountDownLatch(1);
        CountDownLatch firstBuildReleaseLatch = new CountDownLatch(1);

        ConfigDrivenRegistry<ClosableResource> registry = ConfigDrivenRegistry.forKeys(
                configManager, "test.*", (key, val) -> {
                    int count = factoryCallCount.incrementAndGet();
                    if (count == 1) {
                        firstBuildEnterLatch.countDown();
                        try {
                            firstBuildReleaseLatch.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return new ClosableResource(val, closeCount);
                });

        configSource.putAndRefresh("test.k1", "v1");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ClosableResource> f1 = executor.submit(() -> registry.get("k1"));

            Assert.assertTrue("First build must be entered",
                    firstBuildEnterLatch.await(5, TimeUnit.SECONDS));

            // 在首建持锁期间，由另一线程触发热重载配置变更
            Future<?> f2 = executor.submit(() -> configSource.putAndRefresh("test.k1", "v2"));

            // 释放首建阻塞，允许其先完成写入，随后热重载再获取锁并安全替换
            firstBuildReleaseLatch.countDown();

            ClosableResource r1 = f1.get(5, TimeUnit.SECONDS);
            f2.get(5, TimeUnit.SECONDS);

            ClosableResource r2 = registry.get("k1");

            Assert.assertNotNull(r1);
            Assert.assertNotNull(r2);
            Assert.assertNotSame("Reloaded instance must be a new object", r1, r2);
            Assert.assertEquals("v1", r1.getValue());
            Assert.assertEquals("v2", r2.getValue());

            Assert.assertTrue("Old instance must be closed", r1.isClosed());
            Assert.assertFalse("New instance must remain open", r2.isClosed());
            Assert.assertEquals("Close count must be 1", 1, closeCount.get());
            Assert.assertEquals("Factory must be executed exactly twice", 2, factoryCallCount.get());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * destroy 后触发配置变更：断言缓存为空、无实例复活
     */
    @Test
    public void testDestroyThenConfigChangeNoResurrection() {
        AtomicInteger factoryCallCount = new AtomicInteger(0);
        AtomicInteger closeCount = new AtomicInteger(0);

        ConfigDrivenRegistry<ClosableResource> registry = ConfigDrivenRegistry.forKeys(
                configManager, "test.*", (key, val) -> {
                    factoryCallCount.incrementAndGet();
                    return new ClosableResource(val, closeCount);
                });

        configSource.putAndRefresh("test.k1", "v1");
        ClosableResource r1 = registry.get("k1");
        Assert.assertNotNull(r1);
        Assert.assertEquals(1, factoryCallCount.get());
        Assert.assertEquals(0, closeCount.get());

        // 销毁注册表
        registry.destroy();
        Assert.assertTrue(registry.isDestroyed());
        Assert.assertTrue(r1.isClosed());
        Assert.assertEquals(1, closeCount.get());
        Assert.assertNull("get() on destroyed registry must return null", registry.get("k1"));

        // 销毁后触发配置更新
        configSource.putAndRefresh("test.k1", "v2");

        // 断言缓存为空、无实例复活
        Assert.assertNull("Registry must remain empty after destroy", registry.get("k1"));
        Assert.assertEquals("Factory must not be called after destroy", 1, factoryCallCount.get());
        Assert.assertEquals("No additional close should happen", 1, closeCount.get());
    }

    /**
     * 在途回调与 destroy 双重检查竞态：
     * safeSwap 通过了入口 destroyed 检查但在等待 buildMonitor 锁时注册表被 destroy()，
     * 获取锁后复查 destroyed 必须为真并直接 return，杜绝向已清空缓存复活僵尸实例。
     */
    @Test
    public void testInFlightCallbackAbortsAfterDestroy() throws Exception {
        AtomicInteger factory2CallCount = new AtomicInteger(0);

        ConfigDrivenRegistry<ClosableResource> registry = ConfigDrivenRegistry.forKeys(
                configManager, "test.*", (key, val) -> new ClosableResource(val, null));

        configSource.putAndRefresh("test.k1", "v1");
        ClosableResource r1 = registry.get("k1");
        Assert.assertNotNull(r1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch holdLockLatch = new CountDownLatch(1);
            CountDownLatch releaseLockLatch = new CountDownLatch(1);

            // 线程 1 调用 safeSwap 占住 buildMonitor
            Future<?> holdFuture = executor.submit(() -> {
                registry.safeSwap("test.k1", () -> {
                    holdLockLatch.countDown();
                    try {
                        releaseLockLatch.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return new ClosableResource("v_temp", null);
                });
            });

            Assert.assertTrue(holdLockLatch.await(5, TimeUnit.SECONDS));

            // 此时 holdFuture 正持有 buildMonitor
            // 线程 2 此时发起在途 safeSwap 回调，通过了 entry destroyed 检查（此时 destroyed==false），但阻塞在 synchronized(buildMonitor)
            Future<?> callbackFuture = executor.submit(() -> {
                registry.safeSwap("test.k2", () -> {
                    factory2CallCount.incrementAndGet();
                    return new ClosableResource("v_zombie", null);
                });
            });

            // 释放 holdFuture 的阻塞，并等待其释放 buildMonitor
            releaseLockLatch.countDown();
            holdFuture.get(5, TimeUnit.SECONDS);

            // 主线程执行 destroy()，清空缓存并置位 destroyed
            registry.destroy();
            Assert.assertTrue(registry.isDestroyed());

            // 等待 callbackFuture 完成
            callbackFuture.get(5, TimeUnit.SECONDS);

            // 断言在途回调获取 buildMonitor 后双重检查命中 destroyed，未调用工厂，未复活僵尸实例
            Assert.assertEquals("Factory must not be called by aborted safeSwap", 0, factory2CallCount.get());
            Assert.assertNull("Cache must have no zombie instance", registry.get("k2"));
            Assert.assertNull("Cache must remain empty", registry.get("k1"));
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 四类事件按预期序列与参数发出（含 swapFailed 场景）
     */
    @Test
    public void testObservabilityAllFourEventsSequenceAndParameters() {
        AtomicInteger closeCount = new AtomicInteger(0);
        ConfigDrivenRegistry<ClosableResource> registry = ConfigDrivenRegistry.forKeys(
                configManager, "test.*", (key, val) -> {
                    if ("error".equals(val)) {
                        throw new IllegalArgumentException("Invalid test configuration: " + val);
                    }
                    return new ClosableResource(val, closeCount);
                });

        List<String> eventLog = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<String> createdKey = new AtomicReference<>();
        AtomicReference<ClosableResource> createdInst = new AtomicReference<>();
        AtomicReference<ClosableResource> swappedOld = new AtomicReference<>();
        AtomicReference<ClosableResource> swappedNew = new AtomicReference<>();
        AtomicReference<Exception> swapFailureCause = new AtomicReference<>();
        AtomicReference<ClosableResource> removedInst = new AtomicReference<>();

        AutoCloseable listenerHandle = registry.addListener(new ConfigDrivenRegistryListener<ClosableResource>() {
            @Override
            public void onInstanceCreated(String instanceKey, ClosableResource instance) {
                eventLog.add("CREATED:" + instanceKey);
                createdKey.set(instanceKey);
                createdInst.set(instance);
            }

            @Override
            public void onInstanceSwapped(String instanceKey, ClosableResource oldInstance, ClosableResource newInstance) {
                eventLog.add("SWAPPED:" + instanceKey);
                swappedOld.set(oldInstance);
                swappedNew.set(newInstance);
            }

            @Override
            public void onInstanceSwapFailed(String instanceKey, Exception cause) {
                eventLog.add("SWAP_FAILED:" + instanceKey);
                swapFailureCause.set(cause);
            }

            @Override
            public void onInstanceRemoved(String instanceKey, ClosableResource instance) {
                eventLog.add("REMOVED:" + instanceKey);
                removedInst.set(instance);
            }
        });

        // 1. 首次惰性加载 -> 触发 onInstanceCreated
        configSource.putAndRefresh("test.k1", "v1");
        ClosableResource r1 = registry.get("k1");
        Assert.assertNotNull(r1);
        Assert.assertEquals("test.k1", createdKey.get());
        Assert.assertSame(r1, createdInst.get());
        Assert.assertEquals(Collections.singletonList("CREATED:test.k1"), eventLog);

        // 2. 正常配置更新 -> 触发 onInstanceSwapped
        configSource.putAndRefresh("test.k1", "v2");
        ClosableResource r2 = registry.get("k1");
        Assert.assertNotNull(r2);
        Assert.assertNotSame(r1, r2);
        Assert.assertSame(r1, swappedOld.get());
        Assert.assertSame(r2, swappedNew.get());
        Assert.assertTrue(r1.isClosed());
        Assert.assertFalse(r2.isClosed());
        Assert.assertEquals(2, eventLog.size());
        Assert.assertEquals("SWAPPED:test.k1", eventLog.get(1));

        // 3. 非法配置更新导致异常 -> 触发 onInstanceSwapFailed，旧实例保留
        configSource.putAndRefresh("test.k1", "error");
        ClosableResource rAfterError = registry.get("k1");
        Assert.assertSame("Old instance must be retained on failure", r2, rAfterError);
        Assert.assertNotNull(swapFailureCause.get());
        Assert.assertTrue(swapFailureCause.get() instanceof IllegalArgumentException);
        Assert.assertEquals(3, eventLog.size());
        Assert.assertEquals("SWAP_FAILED:test.k1", eventLog.get(2));

        // 4. 配置被置空删除 -> 触发 onInstanceRemoved
        configSource.putAndRefresh("test.k1", null);
        Assert.assertNull(registry.get("k1"));
        Assert.assertSame(r2, removedInst.get());
        Assert.assertTrue(r2.isClosed());
        Assert.assertEquals(4, eventLog.size());
        Assert.assertEquals("REMOVED:test.k1", eventLog.get(3));
    }

    /**
     * addListener 返回的句柄可正确注销
     */
    @Test
    public void testListenerDeregistration() throws Exception {
        ConfigDrivenRegistry<String> registry = ConfigDrivenRegistry.forKeys(
                configManager, "test.*", (key, val) -> val);

        AtomicInteger createEvents = new AtomicInteger(0);
        AtomicInteger swapEvents = new AtomicInteger(0);

        AutoCloseable handle = registry.addListener(new ConfigDrivenRegistryListener<String>() {
            @Override
            public void onInstanceCreated(String instanceKey, String instance) {
                createEvents.incrementAndGet();
            }

            @Override
            public void onInstanceSwapped(String instanceKey, String oldInstance, String newInstance) {
                swapEvents.incrementAndGet();
            }
        });

        configSource.putAndRefresh("test.k1", "v1");
        registry.get("k1");
        Assert.assertEquals(1, createEvents.get());

        // 注销监听句柄
        handle.close();

        // 再次更新配置，监听器不应再收到通知
        configSource.putAndRefresh("test.k1", "v2");
        Assert.assertEquals(1, createEvents.get());
        Assert.assertEquals(0, swapEvents.get());
    }

    /**
     * 监听器实现方异常被 warn 吞掉，绝不阻断分发链
     */
    @Test
    public void testListenerExceptionIsolation() {
        ConfigDrivenRegistry<String> registry = ConfigDrivenRegistry.forKeys(
                configManager, "test.*", (key, val) -> val);

        AtomicBoolean badListenerCalled = new AtomicBoolean(false);
        AtomicBoolean goodListenerCalled = new AtomicBoolean(false);

        // 异常监听器
        registry.addListener(new ConfigDrivenRegistryListener<String>() {
            @Override
            public void onInstanceCreated(String instanceKey, String instance) {
                badListenerCalled.set(true);
                throw new RuntimeException("Simulated error in listener");
            }
        });

        // 正常监听器（注册在异常监听器之后）
        registry.addListener(new ConfigDrivenRegistryListener<String>() {
            @Override
            public void onInstanceCreated(String instanceKey, String instance) {
                goodListenerCalled.set(true);
            }
        });

        configSource.putAndRefresh("test.k1", "v1");
        String result = registry.get("k1");

        Assert.assertEquals("v1", result);
        Assert.assertTrue("Bad listener should have been executed", badListenerCalled.get());
        Assert.assertTrue("Good listener should not be blocked by bad listener error", goodListenerCalled.get());
    }

    /**
     * removeAndClose 与 destroy 保持幂等
     */
    @Test
    public void testIdempotency() {
        AtomicInteger closeCount = new AtomicInteger(0);
        ConfigDrivenRegistry<ClosableResource> registry = ConfigDrivenRegistry.forKeys(
                configManager, "test.*", (key, val) -> new ClosableResource(val, closeCount));

        configSource.putAndRefresh("test.k1", "v1");
        ClosableResource r1 = registry.get("k1");
        Assert.assertNotNull(r1);

        AtomicInteger removeEventCount = new AtomicInteger(0);
        registry.addListener(new ConfigDrivenRegistryListener<ClosableResource>() {
            @Override
            public void onInstanceRemoved(String instanceKey, ClosableResource instance) {
                removeEventCount.incrementAndGet();
            }
        });

        // 首次移除并关闭
        registry.removeAndClose("test.k1");
        Assert.assertEquals(1, closeCount.get());
        Assert.assertEquals(1, removeEventCount.get());
        Assert.assertTrue(r1.isClosed());

        // 重复调用 removeAndClose 必须幂等，不重复调用 close 也不重复分发 removed 事件
        registry.removeAndClose("test.k1");
        Assert.assertEquals(1, closeCount.get());
        Assert.assertEquals(1, removeEventCount.get());

        // destroy 幂等性验证
        registry.destroy();
        Assert.assertTrue(registry.isDestroyed());

        registry.destroy();
        Assert.assertTrue(registry.isDestroyed());
        Assert.assertEquals(1, closeCount.get());
    }

    /**
     * 前缀树模式下的监听器与指纹回写
     */
    @Test
    public void testPrefixModeObservabilityAndFingerprint() {
        AtomicInteger factoryCount = new AtomicInteger(0);
        ConfigDrivenRegistry<PrefixResource> registry = ConfigDrivenRegistry.forPrefix(
                configManager, "server", reader -> {
                    factoryCount.incrementAndGet();
                    return new PrefixResource(reader.getString("host"), reader.getInt("port", 0));
                });

        List<String> events = Collections.synchronizedList(new ArrayList<>());
        registry.addListener(new ConfigDrivenRegistryListener<PrefixResource>() {
            @Override
            public void onInstanceCreated(String instanceKey, PrefixResource instance) {
                events.add("CREATED:" + instanceKey + ":" + instance.port);
            }

            @Override
            public void onInstanceSwapped(String instanceKey, PrefixResource oldInstance, PrefixResource newInstance) {
                events.add("SWAPPED:" + instanceKey + ":" + oldInstance.port + "->" + newInstance.port);
            }

            @Override
            public void onInstanceRemoved(String instanceKey, PrefixResource instance) {
                events.add("REMOVED:" + instanceKey);
            }
        });

        configSource.put("server.host", "localhost");
        configSource.putAndRefresh("server.port", "8080");

        PrefixResource s1 = registry.get();
        Assert.assertNotNull(s1);
        Assert.assertEquals(8080, s1.port);
        Assert.assertEquals(1, factoryCount.get());
        Assert.assertEquals(Collections.singletonList("CREATED:server:8080"), events);

        // 重复刷新相同配置，指纹匹配，不应重复触发重建与事件
        configSource.putAndRefresh("server.port", "8080");
        Assert.assertEquals(1, factoryCount.get());
        Assert.assertEquals(1, events.size());

        // 修改端口，触发热更新与 onInstanceSwapped
        configSource.putAndRefresh("server.port", "8081");
        PrefixResource s2 = registry.get();
        Assert.assertEquals(8081, s2.port);
        Assert.assertEquals(2, factoryCount.get());
        Assert.assertEquals(2, events.size());
        Assert.assertEquals("SWAPPED:server:8080->8081", events.get(1));

        // 删除整子树，触发 onInstanceRemoved
        configSource.put("server.host", null);
        configSource.putAndRefresh("server.port", null);
        Assert.assertNull(registry.get());
        Assert.assertEquals(3, events.size());
        Assert.assertEquals("REMOVED:server", events.get(2));
    }

    private static class ClosableResource implements AutoCloseable {
        private final String value;
        private final AtomicInteger closeCount;
        private volatile boolean closed;

        ClosableResource(String value, AtomicInteger closeCount) {
            this.value = value;
            this.closeCount = closeCount;
        }

        public String getValue() {
            return value;
        }

        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            this.closed = true;
            if (closeCount != null) {
                closeCount.incrementAndGet();
            }
        }
    }

    private static class PrefixResource {
        final String host;
        final int port;

        PrefixResource(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }
}
