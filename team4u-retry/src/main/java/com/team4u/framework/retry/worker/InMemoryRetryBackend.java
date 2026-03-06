package com.team4u.framework.retry.worker;

/**
 * 单进程内存版 RetryBackend，适合测试和本地调试。
 */
public class InMemoryRetryBackend extends AbstractQueueingRetryBackend {

    public InMemoryRetryBackend() {
        this(30_000L);
    }

    public InMemoryRetryBackend(long pendingRecoverAfterMillis) {
        super(pendingRecoverAfterMillis);
    }
}
