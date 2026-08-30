package com.team4u.framework.flow.durable;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存型 CAS 快照存储实现（用于测试与本地单机执行）。
 *
 * @author jay.wu
 */
public class InMemoryDurableStore implements DurableStore {

    private final ConcurrentMap<String, DurableSnapshot> store = new ConcurrentHashMap<>();

    private String key(String flowId, String executionId) {
        return flowId + ":" + executionId;
    }

    @Override
    public DurableSnapshot load(String flowId, String executionId) {
        return store.get(key(flowId, executionId));
    }

    @Override
    public synchronized boolean save(DurableSnapshot snapshot, long expectedRevision) {
        String k = key(snapshot.flowId(), snapshot.executionId());
        DurableSnapshot existing = store.get(k);

        if (existing == null) {
            if (expectedRevision == 0) {
                store.put(k, snapshot);
                return true;
            }
            return false;
        }

        if (existing.revision() == expectedRevision) {
            store.put(k, snapshot);
            return true;
        }

        return false;
    }

    public void clear() {
        store.clear();
    }
}
