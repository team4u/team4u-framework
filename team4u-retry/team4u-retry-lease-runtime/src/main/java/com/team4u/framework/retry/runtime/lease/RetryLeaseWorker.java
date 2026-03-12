package com.team4u.framework.retry.runtime.lease;

import com.team4u.framework.lease.api.LeaseRuntimeClient;
import com.team4u.framework.lease.runtime.LeaseWorker;
import com.team4u.framework.lease.runtime.LeaseWorkerPolicy;
import com.team4u.framework.retry.managed.recovery.RecoveryHandlerRegistry;

/**
 * 重试恢复执行器（Retry Lease Worker）。
 * <p>
 * 该类负责启动后台轮询线程，从租约队列中抢占待重试的任务，并分发给对应的 {@link StringRecoveryHandler} 执行。
 * 它本质上是对租约系统 {@link LeaseWorker} 的薄包装，并预置了重试专属的处理器适配逻辑。
 * </p>
 */
public class RetryLeaseWorker implements Runnable, AutoCloseable {

    /**
     * 实际执行租约轮询和抢占逻辑的底层工作者
     */
    private final LeaseWorker delegate;
    /**
     * 当前节点的恢复处理器注册中心
     */
    private final RecoveryHandlerRegistry registry;

    public RetryLeaseWorker(LeaseRuntimeClient runtimeClient) {
        this(runtimeClient, resolveGlobalRegistry(), LeaseWorkerPolicy.builder().build());
    }

    public RetryLeaseWorker(LeaseRuntimeClient runtimeClient, RecoveryHandlerRegistry registry) {
        this(runtimeClient, registry, LeaseWorkerPolicy.builder().build());
    }

    public RetryLeaseWorker(
            LeaseRuntimeClient runtimeClient,
            RecoveryHandlerRegistry registry,
            LeaseWorkerPolicy policy) {
        this.registry = registry;
        // 构建适配器，将 RecoveryHandlerRegistry 转化为 Lease 系统能识别的 HandlerRegistry
        this.delegate = new LeaseWorker(
                runtimeClient,
                new RecoveryHandlerRegistryLeaseAdapter(registry, RetryLeaseQueues.DEFAULT_RECOVERY_QUEUE),
                policy);
    }

    private static RecoveryHandlerRegistry resolveGlobalRegistry() {
        RecoveryHandlerRegistry registry = RecoveryHandlerRegistry.global();
        registry.autoScan();
        return registry;
    }

    /**
     * 以默认线程名启动后台工作者。
     */
    public void start() {
        delegate.start();
    }

    /**
     * 以指定线程名启动后台工作者，便于在多 runtime 场景下通过线程池区分日志。
     */
    public void start(String threadName) {
        delegate.start(threadName);
    }

    /**
     * 触发停止流程，不再抢占新任务，但已持有的任务会尝试继续执行。
     */
    public void shutdown() {
        delegate.shutdown();
    }

    /**
     * 优雅停止。
     *
     * @param timeoutMillis 最大等待已领用任务执行完毕的时间（毫秒）
     * @return 若在超时时间内所有任务执行完毕并正常关闭则返回 true
     */
    public boolean shutdownGracefully(long timeoutMillis) {
        return delegate.shutdownGracefully(timeoutMillis);
    }

    /**
     * 强制立即停止。
     */
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

    /**
     * 向该工作者注册一个新的恢复处理器。
     */
    public void register(StringRecoveryHandler handler) {
        registry.register(handler);
    }
}
