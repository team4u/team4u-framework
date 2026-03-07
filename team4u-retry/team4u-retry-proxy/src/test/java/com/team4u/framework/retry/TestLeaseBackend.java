package com.team4u.framework.retry;

import com.team4u.framework.retry.backend.RetryBackend;

/**
 * 基于 retry backend SPI 的测试辅助实现。
 */
public abstract class TestLeaseBackend implements RetryBackend {

    public abstract String saveIntent(String taskType, String payload);

    public abstract void completeIntent(String intentId);

    public abstract void markTerminalFailure(String intentId, Throwable cause);

    public abstract void submitForDelay(String intentId, String taskType, String payload, long delay);

    @Override
    public String prepare(String taskType, String payload) {
        return saveIntent(taskType, payload);
    }

    @Override
    public void handoff(String intentId, long delayMillis) {
        submitForDelay(intentId, null, null, delayMillis);
    }

    @Override
    public void complete(String intentId) {
        completeIntent(intentId);
    }
}
