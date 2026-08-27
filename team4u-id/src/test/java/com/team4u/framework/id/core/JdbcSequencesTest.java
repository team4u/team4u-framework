package com.team4u.framework.id.core;

import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.jdbc.JdbcKvStore;
import com.team4u.framework.kv.test.TestKvContext.SettableClock;
import org.h2.jdbcx.JdbcConnectionPool;

import java.time.Clock;

/**
 * JDBC 存储（H2）的序号服务契约测试
 * <p>
 * 覆盖 {@code kv_counter} 表的行锁计数路径与号段批量取号路径。
 *
 * @author jay.wu
 */
public class JdbcSequencesTest extends AbstractSequencesContractTest {

    private final SettableClock clock = new SettableClock(0L);
    private final JdbcConnectionPool dataSource = JdbcConnectionPool.create(
            "jdbc:h2:mem:seq_contract_" + System.nanoTime()
                    + ";DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", "");

    @Override
    protected KvStore createStore() {
        return new JdbcKvStore(dataSource, new JdbcKvStore.Config(), clock);
    }

    @Override
    protected Clock clock() {
        return clock;
    }

    @Override
    protected void advanceMillis(long millis) {
        clock.advance(millis);
    }
}
