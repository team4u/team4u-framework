package com.team4u.framework.kv.jdbc;

import com.team4u.framework.kv.CasCapable;
import com.team4u.framework.kv.CounterCapable;
import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.kv.test.AbstractCounterTtlContractTest;
import com.team4u.framework.kv.test.TestKvContext;
import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

/**
 * JDBC 存储（H2）的契约测试（含计数 TTL 契约）
 *
 * @author jay.wu
 */
public class JdbcKvStoreContractTest extends AbstractCounterTtlContractTest {

    private final TestKvContext.SettableClock clock = new TestKvContext.SettableClock(0L);
    private final JdbcConnectionPool dataSource = JdbcConnectionPool.create(
            "jdbc:h2:mem:kv_contract_" + System.nanoTime()
                    + ";DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", "");

    @Override
    protected KvStore createStore() {
        return new JdbcKvStore(dataSource, new JdbcKvStore.Config(), clock);
    }

    @Override
    protected long nowMillis() {
        return clock.millis();
    }

    @Override
    protected void advanceMillis(long millis) {
        clock.advance(millis);
    }

    /**
     * H2 下验证 kv_counter 表 expire_at 列驱动的过期重置：
     * 过期后首次递增返回 delta，且既有 TTL 不被后续递增刷新
     */
    @Test
    public void counterRowExpiresAndRestartsOnH2() {
        CounterCapable counter = (CounterCapable) store;
        SpaceKey key = SpaceKey.of("h2", "counter-ttl");

        assertEquals(5, counter.incrementAndGet(key, 5, 1000));
        clock.advance(500);
        assertEquals(6, counter.incrementAndGet(key, 1, 1000));
        clock.advance(500);
        assertEquals("expired counter must restart from zero", 2,
                counter.incrementAndGet(key, 2, 1000));
        clock.advance(500);
        assertEquals("restarted counter keeps its new ttl", 3,
                counter.incrementAndGet(key, 1, 1000));
        clock.advance(500);
        // 重置后的键再次到期，且 ttl<=0 的重建为永不过期
        assertEquals(1, counter.incrementAndGet(key, 1, 0));
    }

    /**
     * H2 下验证 compareAndExpire 的保序条件 UPDATE：
     * 令牌匹配才续约、晚到续约不回缩、过期后不复活、0 为永不过期
     */
    @Test
    public void compareAndExpireSemanticsOnH2() {
        CasCapable cas = (CasCapable) store;
        SpaceKey key = SpaceKey.of("h2", "cas-expire");
        store.put(key, KvRecord.of("token-a", 1000, nowMillis()), PutMode.SET);

        // 令牌匹配：续约成功，值不变
        advanceMillis(500);
        assertTrue(cas.compareAndExpire(key, "token-a", nowMillis() + 2000));
        assertEquals("token-a", store.get(key).getValue());
        assertEquals(nowMillis() + 2000, store.get(key).getExpireAt());

        // 令牌不匹配：不续约
        assertFalse(cas.compareAndExpire(key, "token-b", 99_999));
        assertEquals(nowMillis() + 2000, store.get(key).getExpireAt());

        // 晚到续约：返回 true 但不回缩租约
        assertTrue(cas.compareAndExpire(key, "token-a", nowMillis() + 100));
        assertEquals("晚到续约不得缩短租约", nowMillis() + 2000, store.get(key).getExpireAt());

        // 0 = 永不过期
        assertTrue(cas.compareAndExpire(key, "token-a", 0));
        assertEquals(0, store.get(key).getExpireAt());

        // 过期后不复活
        store.remove(key);
        store.put(key, KvRecord.of("token-c", 1000, nowMillis()), PutMode.SET);
        advanceMillis(1000);
        assertFalse(cas.compareAndExpire(key, "token-c", nowMillis() + 5000));
        assertNull(store.get(key));
    }
}
