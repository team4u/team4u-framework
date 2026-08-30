package com.team4u.framework.id.core;

import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.test.TestKvContext;

import java.time.Clock;

/**
 * 内存存储的序号服务契约测试：所有计数后端的行为基准
 *
 * @author jay.wu
 */
public class InMemorySequencesTest extends AbstractSequencesContractTest {

    private final TestKvContext kv = TestKvContext.create();

    @Override
    protected KvStore createStore() {
        return kv.store();
    }

    @Override
    protected Clock clock() {
        return kv.clock();
    }

    @Override
    protected void advanceMillis(long millis) {
        kv.advanceMillis(millis);
    }
}
