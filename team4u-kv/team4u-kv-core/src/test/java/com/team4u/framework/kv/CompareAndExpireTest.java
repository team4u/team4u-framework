package com.team4u.framework.kv;

import com.team4u.framework.kv.memory.InMemoryKvStore;
import com.team4u.framework.kv.support.SettableClock;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link CasCapable#compareAndExpire} 内存实现的语义测试：
 * 令牌匹配则续约、不匹配不续约、过期后不复活、晚到续约不缩短租约
 *
 * @author jay.wu
 */
public class CompareAndExpireTest {

    private SettableClock clock;
    private InMemoryKvStore store;
    private CasCapable cas;

    @Before
    public void setUp() {
        clock = new SettableClock(1_000L);
        store = new InMemoryKvStore(clock);
        cas = store;
    }

    @Test
    public void matchingTokenRenewsLease() {
        SpaceKey key = SpaceKey.of("lock", "job");
        store.put(key, KvRecord.of("token-a", 1000, clock.millis()), PutMode.SET);

        clock.advance(500);
        assertTrue(cas.compareAndExpire(key, "token-a", clock.millis() + 2000));

        KvRecord renewed = store.get(key);
        assertEquals("token-a", renewed.getValue());
        assertEquals("值不变，仅过期时间更新", clock.millis() + 2000, renewed.getExpireAt());
    }

    @Test
    public void mismatchedTokenDoesNotRenew() {
        SpaceKey key = SpaceKey.of("lock", "job");
        store.put(key, KvRecord.of("token-a", 1000, clock.millis()), PutMode.SET);
        long originalExpireAt = store.get(key).getExpireAt();

        clock.advance(100);
        assertFalse("他人令牌不得续约", cas.compareAndExpire(key, "token-b", 99_999));

        assertEquals("记录过期时间保持不变", originalExpireAt, store.get(key).getExpireAt());
        assertEquals("token-a", store.get(key).getValue());
    }

    @Test
    public void missingKeyReturnsFalse() {
        assertFalse(cas.compareAndExpire(SpaceKey.of("lock", "missing"), "token-a", 99_999));
    }

    @Test
    public void expiredKeyDoesNotResurrect() {
        SpaceKey key = SpaceKey.of("lock", "job");
        store.put(key, KvRecord.of("token-a", 1000, clock.millis()), PutMode.SET);

        clock.advance(1000);
        assertFalse("已过期记录不得被复活", cas.compareAndExpire(key, "token-a", clock.millis() + 5000));
        assertNull(store.get(key));
    }

    @Test
    public void lateRenewNeverShortensLease() {
        SpaceKey key = SpaceKey.of("lock", "job");
        store.put(key, KvRecord.of("token-a", 1000, clock.millis()), PutMode.SET);

        // 先以较晚的过期时间续约
        clock.advance(100);
        assertTrue(cas.compareAndExpire(key, "token-a", clock.millis() + 10_000));

        // 再以较早的过期时间（模拟乱序到达的晚到心跳）续约：不得回缩
        assertTrue("持有者校验通过仍返回 true", cas.compareAndExpire(key, "token-a", clock.millis() + 1000));
        assertEquals("晚到的续约不得缩短租约",
                clock.millis() + 10_000, store.get(key).getExpireAt());
    }

    @Test
    public void zeroExpireAtMeansNeverExpire() {
        SpaceKey key = SpaceKey.of("lock", "job");
        store.put(key, KvRecord.of("token-a", 1000, clock.millis()), PutMode.SET);

        clock.advance(100);
        assertTrue(cas.compareAndExpire(key, "token-a", 0L));

        clock.advance(1_000_000);
        assertEquals("0 = 永不过期", "token-a", store.get(key).getValue());
        assertEquals(0, store.get(key).getExpireAt());
    }

    @Test
    public void renewingPermanentRecordWithFiniteLeaseIsIgnored() {
        SpaceKey key = SpaceKey.of("lock", "job");
        store.put(key, KvRecord.of("token-a"), PutMode.SET);   // 永不过期

        assertTrue(cas.compareAndExpire(key, "token-a", clock.millis() + 1000));
        assertEquals("永不过期（0）视为无穷大，有限新值不得回缩", 0, store.get(key).getExpireAt());
    }
}
