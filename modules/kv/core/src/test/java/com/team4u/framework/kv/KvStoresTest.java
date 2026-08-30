package com.team4u.framework.kv;

import com.team4u.framework.kv.memory.InMemoryKvStore;
import com.team4u.framework.kv.observed.ObservedStore;
import com.team4u.framework.kv.tiered.TieredStore;
import org.junit.Test;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * 装饰链解析工具测试：innermost 剥洋葱、capabilityOf 沿链能力协商
 */
public class KvStoresTest {

    @Test
    public void innermostPeelsDecoratorChain() {
        InMemoryKvStore inner = new InMemoryKvStore();
        TieredStore tiered = new TieredStore(inner, 60_000, new TieredStore.Config());
        ObservedStore observed = new ObservedStore(tiered);

        assertSame("逐层剥开装饰器后应返回最内层真实存储", inner, KvStores.innermost(observed));
        assertSame("中间层解析同样到达最内层", inner, KvStores.innermost(tiered));
    }

    @Test
    public void innermostOfPlainStoreReturnsItself() {
        InMemoryKvStore store = new InMemoryKvStore();
        assertSame("非装饰存储原样返回", store, KvStores.innermost(store));
    }

    @Test
    public void capabilityOfFindsCapabilityThroughChain() {
        InMemoryKvStore inner = new InMemoryKvStore();
        KvStore chain = new ObservedStore(new TieredStore(inner, 60_000, new TieredStore.Config()));

        assertSame("装饰链下的 CasCapable 应解析到内层实现", inner,
                KvStores.capabilityOf(chain, CasCapable.class));
        assertSame("装饰链下的 ScanCapable 应解析到内层实现", inner,
                KvStores.capabilityOf(chain, ScanCapable.class));
    }

    @Test
    public void capabilityOfReturnsNullWhenChainNeverImplements() {
        KvStore chain = new ObservedStore(
                new TieredStore(new InMemoryKvStore(), 60_000, new TieredStore.Config()));

        assertNull("整条链均不支持的能力返回 null（InMemory 非原生 TTL）",
                KvStores.capabilityOf(chain, NativeTtlCapable.class));
    }

    @Test
    public void capabilityOfOnDirectImplementationReturnsItself() {
        InMemoryKvStore store = new InMemoryKvStore();

        assertSame(store, KvStores.capabilityOf(store, CasCapable.class));
        assertSame(store, KvStores.capabilityOf(store, WatchCapable.class));
    }

    @Test(expected = NullPointerException.class)
    public void nullStoreRejected() {
        KvStores.innermost(null);
    }
}
