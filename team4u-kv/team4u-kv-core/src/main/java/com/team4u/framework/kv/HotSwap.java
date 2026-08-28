package com.team4u.framework.kv;

/**
 * KV-local hot-swap contract implemented by HotSwapStore proxies.
 *
 * @author jay.wu
 */
public interface HotSwap {

    /**
     * Atomically replaces the current delegate.
     *
     * @param newDelegate the non-null replacement {@link KvStore}
     * @return the replaced delegate
     */
    Object hotswap(Object newDelegate);
}
