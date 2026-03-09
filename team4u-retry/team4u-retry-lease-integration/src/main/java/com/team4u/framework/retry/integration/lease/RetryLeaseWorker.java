package com.team4u.framework.retry.integration.lease;

import com.team4u.framework.lease.api.LeaseRuntimeClient;
import com.team4u.framework.lease.runtime.LeaseWorker;
import com.team4u.framework.lease.runtime.LeaseWorkerPolicy;
import com.team4u.framework.retry.recovery.RecoveryHandler;
import com.team4u.framework.retry.recovery.RecoveryHandlerRegistry;

/**
 * 基于 team4u-lease 的重试恢复工作者
 */
public class RetryLeaseWorker implements Runnable, AutoCloseable {

    private final LeaseWorker delegate;
    private final RecoveryHandlerRegistry registry;

    public RetryLeaseWorker(LeaseRuntimeClient runtimeClient) {
        this(runtimeClient, RecoveryHandlerRegistry.global(), LeaseWorkerPolicy.builder().build());
    }

    public RetryLeaseWorker(LeaseRuntimeClient runtimeClient, RecoveryHandlerRegistry registry) {
        this(runtimeClient, registry, LeaseWorkerPolicy.builder().build());
    }

    public RetryLeaseWorker(LeaseRuntimeClient runtimeClient,
                            RecoveryHandlerRegistry registry,
                            LeaseWorkerPolicy policy) {
        this.registry = registry;
        this.delegate = new LeaseWorker(
                runtimeClient,
                new RecoveryHandlerRegistryLeaseAdapter(registry, RetryLeaseQueues.DEFAULT_RECOVERY_QUEUE),
                policy);
    }

    public void start() {
        delegate.start();
    }

    public void start(String threadName) {
        delegate.start(threadName);
    }

    public void shutdown() {
        delegate.shutdown();
    }

    public boolean shutdownGracefully(long timeoutMillis) {
        return delegate.shutdownGracefully(timeoutMillis);
    }

    public synchronized void shutdownNow() {
        delegate.shutdownNow();
    }

    @Override
    public void run() {
        delegate.run();
    }

    @Override
    public void close() {
        delegate.close();
    }

    public void register(RecoveryHandler<?> handler) {
        registry.register(handler);
    }
}
