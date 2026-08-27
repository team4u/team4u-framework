package com.team4u.framework.kv.jdbc;

import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.test.AbstractKvStoreContractTest;
import com.team4u.framework.kv.test.TestKvContext;
import org.h2.jdbcx.JdbcConnectionPool;

/**
 * JDBC 存储（H2）的契约测试
 *
 * @author jay.wu
 */
public class JdbcKvStoreContractTest extends AbstractKvStoreContractTest {

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
}
