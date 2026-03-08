package com.team4u.framework.retry;

import com.team4u.framework.retry.backend.RetryBackend;
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
    public void complete(String taskId) {
    }

    @Override
    public void saveProgress(RetryTaskSnapshot snapshot) {
    }

    @Override
    public void terminalFail(String taskId, Throwable cause) {
    }
}
