package com.team4u.framework.kv.test;

import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.memory.InMemoryKvStore;

/**
 * 内存存储的契约测试：所有 KvStore 实现的行为基准
 * （含计数 TTL 与计分窗口契约）
 *
 * @author jay.wu
 */
public class InMemoryKvStoreContractTest extends AbstractScoredWindowCapableContractTest {

    private final TestKvContext context = TestKvContext.create();

    @Override
    protected KvStore createStore() {
        return new InMemoryKvStore(context.clock());
    }

    @Override
    protected long nowMillis() {
        return context.clock().millis();
    }

    @Override
    protected void advanceMillis(long millis) {
        context.advanceMillis(millis);
    }
}
