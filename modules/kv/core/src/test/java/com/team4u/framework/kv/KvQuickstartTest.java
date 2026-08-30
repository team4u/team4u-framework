package com.team4u.framework.kv;

import com.team4u.framework.kv.hotswap.HotSwapStore;
import com.team4u.framework.kv.memory.InMemoryKvStore;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

public class KvQuickstartTest {

    @Test
    public void rawPublicStoreAndHotSwapRunWithoutAdapterDependencies() {
        InMemoryKvStore store = new InMemoryKvStore();
        SpaceKey key = SpaceKey.of("quickstart", "first");

        store.put(key, KvRecord.of("value-1", 60_000L, System.currentTimeMillis()), PutMode.SET);
        Assert.assertEquals("value-1", store.get(key).getValue());
        Assert.assertFalse(store.put(key, KvRecord.of("value-2", 60_000L, System.currentTimeMillis()),
                PutMode.IF_ABSENT));
        Assert.assertTrue(store.expire(key, 30_000L));
        Assert.assertTrue(store.remove(key));
        Assert.assertNull(store.get(key));

        InMemoryKvStore replacement = new InMemoryKvStore();
        replacement.put(key, KvRecord.of("replacement", 0L, System.currentTimeMillis()), PutMode.SET);

        KvStore hotSwapStore = HotSwapStore.wrap(store);
        AtomicReference<Object> oldDelegate = new AtomicReference<>();
        Thread hotSwapThread = new Thread(() ->
                oldDelegate.set(((HotSwap) hotSwapStore).hotswap(replacement)));
        hotSwapThread.start();
        try {
            hotSwapThread.join(10_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }

        Assert.assertSame(store, oldDelegate.get());
        Assert.assertEquals("replacement", hotSwapStore.get(key).getValue());
    }
}
