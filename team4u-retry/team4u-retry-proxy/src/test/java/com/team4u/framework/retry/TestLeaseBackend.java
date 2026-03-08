package com.team4u.framework.retry;

import com.team4u.framework.retry.backend.RetryTaskSnapshot;
import com.team4u.framework.retry.backend.RetryBackend;

/**
 * 基于 RetryPersistenceAdapter 的测试辅助实现（Proxy 模块冗余副本）。
 */
public abstract class TestLeaseBackend implements RetryBackend {

    @Override
    public void save(RetryTaskSnapshot snapshot) {
    }

    @Override
    public void handoff(String taskId, long delayMillis) {
    }

    @Override
    public void delete(String taskId) {
    }
}
