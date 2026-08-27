package com.team4u.framework.kv.hotswap;

import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.proxy.support.Swappable;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HotSwapStoreTest {

    @Test
    public void proxyImplementsKvStoreAndSwappable() {
        KvStore proxy = HotSwapStore.wrap(new TrackingStore());

        assertTrue(proxy instanceof Swappable);
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

    @Test
    public void swapAndCloseQuietlyClosesOldStore() {
        TrackingStore oldStore = new TrackingStore();
        KvStore proxy = HotSwapStore.wrap(oldStore);

        HotSwapStore.swapAndCloseQuietly(proxy, new TrackingStore());

        assertTrue(oldStore.closed);
    }

    @Test
    public void swapWithoutCloseKeepsOldStoreOpen() {
        TrackingStore oldStore = new TrackingStore();
        KvStore proxy = HotSwapStore.wrap(oldStore);

        HotSwapStore.swap(proxy, new TrackingStore(), false);

        assertFalse(oldStore.closed);
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
        TrackingStore oldStore = new TrackingStore();
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

    /**
     * 记录关闭状态的存储桩
     */
    static class TrackingStore implements KvStore, AutoCloseable {

        boolean closed;

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

        @Override
        public void close() {
            closed = true;
        }
    }
}
