package com.team4u.framework.retry;

import com.team4u.framework.retry.backend.RetryBackend;
import com.team4u.framework.retry.backend.RetryCloseRequest;
import com.team4u.framework.retry.backend.RetryTaskSnapshot;

/**
 * 基于 RetryPersistenceAdapter 的测试辅助实现。
 */
public abstract class TestLeaseBackend implements RetryBackend {

    @Override
    public void prepare(RetryTaskSnapshot snapshot) {
    }

    @Override
    public void handoff(String taskId, long delayMillis) {
    }

    @Override
    public void close(String taskId, RetryCloseRequest request) {
    }

    @Override
    public void saveProgress(RetryTaskSnapshot snapshot) {
    }
}
