package com.team4u.framework.kv.jdbc;

import com.team4u.framework.kv.CounterCapable;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.kv.test.AbstractCounterTtlContractTest;
import com.team4u.framework.kv.test.TestKvContext;
import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

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
}
