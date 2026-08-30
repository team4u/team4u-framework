package com.team4u.framework.kv.hotswap;

import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.KvStores;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.kv.StoreWrapper;
import com.team4u.framework.kv.memory.InMemoryKvStore;
import com.team4u.framework.kv.tiered.TieredStore;
import com.team4u.framework.kv.HotSwap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class HotSwapStoreTest {

    @Test
    public void proxyImplementsOnlyKvStoreAndHotSwapByDefault() {
        KvStore proxy = HotSwapStore.wrap(new TrackingStore());

        assertTrue(proxy instanceof HotSwap);
        assertFalse(proxy instanceof StoreWrapper);
        assertFalse(proxy instanceof AutoCloseable);

        proxy.put(SpaceKey.of("user", "u1"), KvRecord.of("v1"), PutMode.SET);
        assertEquals("v1", proxy.get(SpaceKey.of("user", "u1")).getValue());
    }

    @Test
    public void swapKeepsReferenceAndSwitchesBackend() {
        KvStore proxy = HotSwapStore.wrap(new TrackingStore());
        SpaceKey key = SpaceKey.of("user", "u1");

        proxy.put(key, KvRecord.of("v1"), PutMode.SET);

        TrackingStore newStore = new TrackingStore();
        newStore.put(key, KvRecord.of("v2"), PutMode.SET);
        HotSwapStore.swap(proxy, newStore, false);

        // 业务引用不变，读取已切换到新后端
        assertEquals("v2", proxy.get(key).getValue());
    }

    /**
     * 热交换代理透传 StoreWrapper：unwrap 转发到当前委托，
     * 能力解析在交换后自动指向新存储的内层
     */
    @Test
    public void wrapPreservesUnwrapThroughSwap() {
        InMemoryKvStore inner1 = new InMemoryKvStore();
        InMemoryKvStore inner2 = new InMemoryKvStore();
        KvStore proxy = HotSwapStore.wrap(
                new TieredStore(inner1, 60_000, new TieredStore.Config()));

        assertSame("交换前解析到初始存储的内层", inner1, KvStores.innermost(proxy));

        HotSwapStore.swap(proxy, new TieredStore(inner2, 60_000, new TieredStore.Config()), false);

        assertSame("交换后 unwrap 转发到新委托，解析随之切换", inner2, KvStores.innermost(proxy));
    }

    @Test
    public void swapAndCloseQuietlyClosesOldStore() {
        CloseableTrackingStore oldStore = new CloseableTrackingStore();
        KvStore proxy = HotSwapStore.wrap(oldStore);

        HotSwapStore.swapAndCloseQuietly(proxy, new TrackingStore());

        assertTrue(oldStore.closed);
    }

    @Test
    public void swapWithoutCloseKeepsOldStoreOpen() {
        CloseableTrackingStore oldStore = new CloseableTrackingStore();
        KvStore proxy = HotSwapStore.wrap(oldStore);

        HotSwapStore.swap(proxy, new TrackingStore(), false);

        assertFalse(oldStore.closed);
    }

    /**
     * 代理的 close() 经鸭子类型转发到当前存储：换下的旧存储原样返回（未关闭）
     */
    @Test
    public void proxyCloseClosesCurrentStore() throws Exception {
        CloseableTrackingStore initial = new CloseableTrackingStore();
        CloseableTrackingStore current = new CloseableTrackingStore();
        KvStore proxy = HotSwapStore.wrap(initial);

        HotSwapStore.swap(proxy, current, false);
        ((AutoCloseable) proxy).close();

        assertTrue("close 关闭的是当前存储", current.closed);
        assertFalse("旧存储由 swap 的关闭策略负责，此处保持未关闭", initial.closed);
    }

    @Test(expected = IllegalArgumentException.class)
    public void swapOnPlainStoreFailsFast() {
        HotSwapStore.swap(new TrackingStore(), new TrackingStore(), false);
    }

    @Test(expected = NullPointerException.class)
    public void wrapNullStoreFailsFast() {
        HotSwapStore.wrap(null);
    }

    @Test(expected = NullPointerException.class)
    public void swapToNullStoreFailsFast() {
        KvStore proxy = HotSwapStore.wrap(new TrackingStore());
        HotSwapStore.swap(proxy, null, 0L);
    }

    @Test
    public void swapWithGracePeriodClosesLater() throws Exception {
        CloseableTrackingStore oldStore = new CloseableTrackingStore();
        KvStore proxy = HotSwapStore.wrap(oldStore);

        HotSwapStore.swap(proxy, new TrackingStore(), 50);
        assertFalse("宽限期内旧存储未关闭", oldStore.closed);

        // 宽限期后由守护线程关闭
        long deadline = System.currentTimeMillis() + 5000;
        while (!oldStore.closed && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue("宽限期结束后旧存储被关闭", oldStore.closed);
    }

    @Test
    public void rawHotswapAtomicallyReturnsOldStoreAndDoesNotCloseIt() {
        CloseableTrackingStore oldStore = new CloseableTrackingStore();
        TrackingStore newStore = new TrackingStore();
        KvStore proxy = HotSwapStore.wrap(oldStore);

        Object returned = ((HotSwap) proxy).hotswap(newStore);

        assertSame(oldStore, returned);
        assertFalse(oldStore.closed);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rawHotswapRejectsNullDelegate() {
        KvStore proxy = HotSwapStore.wrap(new TrackingStore());

        ((HotSwap) proxy).hotswap(null);
    }

    @Test
    public void capabilitySetIsStableAcrossSwaps() {
        TrackingStore initial = new TrackingStore();
        KvStore proxy = HotSwapStore.wrap(initial);
        assertTrue(proxy instanceof HotSwap);
        assertFalse(proxy instanceof StoreWrapper);
        assertFalse(proxy instanceof AutoCloseable);

        ((HotSwap) proxy).hotswap(new WrappingStore(new TrackingStore()));

        assertFalse("gain after creation is not published", proxy instanceof StoreWrapper);
        assertFalse(proxy instanceof AutoCloseable);
    }

    @Test(expected = IllegalStateException.class)
    public void losingWrapperCapabilityFailsClearlyOnUnwrap() {
        KvStore proxy = HotSwapStore.wrap(new WrappingStore(new TrackingStore()));

        ((HotSwap) proxy).hotswap(new TrackingStore());
        ((StoreWrapper) proxy).unwrap();
    }

    @Test(expected = IllegalStateException.class)
    public void losingCloseCapabilityFailsClearlyOnClose() throws Exception {
        KvStore proxy = HotSwapStore.wrap(new CloseableTrackingStore());

        ((HotSwap) proxy).hotswap(new TrackingStore());
        ((AutoCloseable) proxy).close();
    }
    @Test
    public void delegateExceptionsPassThroughUnwrapped() {
        IllegalStateException failure = new IllegalStateException("delegate failure");
        KvStore proxy = HotSwapStore.wrap(new FailingStore(failure));

        try {
            proxy.get(SpaceKey.of("user", "u1"));
            fail("Expected the delegate's original exception");
        } catch (IllegalStateException actual) {
            assertSame(failure, actual);
        }
    }

    @Test
    public void proxyObjectMethodsUseIdentityAndUsefulToString() {
        KvStore proxy = HotSwapStore.wrap(new TrackingStore());
        TrackingStore other = new TrackingStore();

        assertTrue(proxy.equals(proxy));
        assertFalse(proxy.equals(other));
        assertEquals(proxy.hashCode(), proxy.hashCode());
        assertTrue(proxy.toString().contains("HotSwapStore proxy"));
    }

    @Test
    public void concurrentSwapsKeepEachProxyOutcomeAtomic() throws Exception {
        final KvStore proxy = HotSwapStore.wrap(new TrackingStore());
        final int threads = 8;
        final int swapsPerThread = 100;
        final List<KvStore> oldStores = Collections.synchronizedList(new ArrayList<KvStore>());
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    for (int j = 0; j < swapsPerThread; j++) {
                        oldStores.add((KvStore) ((HotSwap) proxy).hotswap(new TrackingStore()));
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(threads * swapsPerThread, oldStores.size());
        Set<KvStore> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
        distinct.addAll(oldStores);
        assertEquals(oldStores.size(), distinct.size());
    }

    @Test
    public void inFlightCallRetainsTheDelegateCapturedAtItsStart() throws Exception {
        final Gate gate = new Gate();
        final BlockingGetStore oldStore = new BlockingGetStore(gate);
        final KvStore proxy = HotSwapStore.wrap(oldStore);
        final AtomicReference<Object> result = new AtomicReference<>();
        Thread worker = new Thread(() -> result.set(proxy.get(SpaceKey.of("user", "u1"))));
        worker.start();
        gate.awaitStarted();

        TrackingStore replacement = new TrackingStore();
        Object returned = ((HotSwap) proxy).hotswap(replacement);
        gate.open();
        worker.join(10_000L);

        assertSame(oldStore, returned);
        assertSame(Gate.SENTINEL, result.get());
    }

    private static final class Gate {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        void awaitStarted() throws InterruptedException {
            assertTrue(started.await(10, TimeUnit.SECONDS));
        }

        void open() {
            release.countDown();
        }

        static final KvRecord SENTINEL = KvRecord.of("sentinel");

        KvRecord awaitRelease() throws InterruptedException {
            started.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return SENTINEL;
        }
    }

    /**
     * 记录关闭状态的存储桩
     */
    static class TrackingStore implements KvStore {

        private final KvStore delegate = new com.team4u.framework.kv.memory.InMemoryKvStore();

        @Override
        public KvRecord get(SpaceKey key) {
            return delegate.get(key);
        }

        @Override
        public boolean put(SpaceKey key, KvRecord record, PutMode mode) {
            return delegate.put(key, record, mode);
        }

        @Override
        public boolean remove(SpaceKey key) {
            return delegate.remove(key);
        }

        @Override
        public boolean expire(SpaceKey key, long ttlMillis) {
            return delegate.expire(key, ttlMillis);
        }
    }

    static class CloseableTrackingStore extends TrackingStore implements AutoCloseable {

        boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }

    static class WrappingStore implements KvStore, StoreWrapper {

        private final KvStore delegate;

        WrappingStore(KvStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public KvStore unwrap() {
            return delegate;
        }

        @Override
        public KvRecord get(SpaceKey key) {
            return delegate.get(key);
        }

        @Override
        public boolean put(SpaceKey key, KvRecord record, PutMode mode) {
            return delegate.put(key, record, mode);
        }

        @Override
        public boolean remove(SpaceKey key) {
            return delegate.remove(key);
        }

        @Override
        public boolean expire(SpaceKey key, long ttlMillis) {
            return delegate.expire(key, ttlMillis);
        }
    }

    static class FailingStore implements KvStore {

        private final RuntimeException failure;

        FailingStore(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public KvRecord get(SpaceKey key) {
            throw failure;
        }

        @Override
        public boolean put(SpaceKey key, KvRecord record, PutMode mode) {
            throw new UnsupportedOperationException("delegate failure");
        }

        @Override
        public boolean remove(SpaceKey key) {
            throw new UnsupportedOperationException("delegate failure");
        }

        @Override
        public boolean expire(SpaceKey key, long ttlMillis) {
            throw new UnsupportedOperationException("delegate failure");
        }
    }

    static class BlockingGetStore implements KvStore {

        private final Gate gate;

        BlockingGetStore(Gate gate) {
            this.gate = gate;
        }

        @Override
        public KvRecord get(SpaceKey key) {
            try {
                return (KvRecord) gate.awaitRelease();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        @Override
        public boolean put(SpaceKey key, KvRecord record, PutMode mode) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean remove(SpaceKey key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean expire(SpaceKey key, long ttlMillis) {
            throw new UnsupportedOperationException();
        }
    }
}
