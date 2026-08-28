package com.team4u.framework.kv.test;

import com.team4u.framework.kv.CounterCapable;
import com.team4u.framework.kv.SpaceKey;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 计数器 TTL 行为契约测试基类
 * <p>
 * 在 {@link AbstractKvStoreContractTest} 基础上补充
 * {@code incrementAndGet(key, delta, ttlMillis)} 的 TTL 契约：
 * 过期后从 0 重新计数、TTL 不因后续递增刷新、ttl&lt;=0 永不过期。
 * 按 {@code instanceof CounterCapable} 存在性执行，任何支持计数的后端
 * 继承本类即自动获得该套契约（对齐基类「按实现存在性执行」惯例）。
 * </p>
 *
 * @author jay.wu
 */
public abstract class AbstractCounterTtlContractTest extends AbstractKvStoreContractTest {

    @Test
    public void contractCounterExpiresAndRestartsFromZero() {
        if (!(store instanceof CounterCapable)) {
            return;
        }
        CounterCapable counter = (CounterCapable) store;
        SpaceKey key = SpaceKey.of("contract", "counter-ttl");

        assertEquals(3, counter.incrementAndGet(key, 3, 1000));
        advanceMillis(999);
        // 未到期：继续累加
        assertEquals(4, counter.incrementAndGet(key, 1, 1000));

        advanceMillis(1);
        // 到期：首次递增从 0 重新开始，返回值等于 delta
        assertEquals(2, counter.incrementAndGet(key, 2, 1000));
        // 重新开始后的第二次递增正常累加
        assertEquals(3, counter.incrementAndGet(key, 1, 1000));
    }

    @Test
    public void contractCounterTtlNotRefreshedByLaterIncrements() {
        if (!(store instanceof CounterCapable)) {
            return;
        }
        CounterCapable counter = (CounterCapable) store;
        SpaceKey key = SpaceKey.of("contract", "counter-ttl-norefresh");

        counter.incrementAndGet(key, 1, 1000);
        advanceMillis(500);
        // 后续递增携带 TTL 不刷新既有 TTL（仍以首次设置的 deadline 为准）
        counter.incrementAndGet(key, 1, 1000);
        advanceMillis(500);
        assertEquals("TTL must not be refreshed by later increments",
                1, counter.incrementAndGet(key, 1, 1000));
    }

    @Test
    public void contractCounterZeroTtlNeverExpires() {
        if (!(store instanceof CounterCapable)) {
            return;
        }
        CounterCapable counter = (CounterCapable) store;
        SpaceKey key = SpaceKey.of("contract", "counter-no-ttl");

        counter.incrementAndGet(key, 1, 0);
        advanceMillis(24L * 3600_000);
        assertEquals("ttlMillis <= 0 must never expire",
                2, counter.incrementAndGet(key, 1, 0));
    }
}
