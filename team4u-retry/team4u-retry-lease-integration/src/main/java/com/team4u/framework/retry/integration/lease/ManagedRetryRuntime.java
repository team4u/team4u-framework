package com.team4u.framework.retry.integration.lease;

import com.team4u.framework.lease.api.LeaseBackend;
import com.team4u.framework.lease.runtime.LeaseWorkerPolicy;
import com.team4u.framework.retry.client.DefaultManagedRetryClient;
import com.team4u.framework.retry.client.ManagedRetryClient;
import com.team4u.framework.retry.policy.RetryPolicy;
import com.team4u.framework.retry.recovery.RecoveryHandlerRegistry;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * 可启动的 MANAGED 重试运行时。
 */
public class ManagedRetryRuntime implements AutoCloseable {

    private final ManagedRetryClient client;
    private final RecoveryHandlerRegistry registry;
    private final RetryLeaseWorker worker;
    private final String workerThreadName;
    private volatile boolean started;

    ManagedRetryRuntime(
            ManagedRetryClient client,
            RecoveryHandlerRegistry registry,
            RetryLeaseWorker worker,
            String workerThreadName) {
        this.client = client;
        this.registry = registry;
        this.worker = worker;
        this.workerThreadName = workerThreadName;
    }

    /**
     * 创建基于 lease 的 runtime 构建器。
     *
     * @param backend LeaseBackend 实例
     * @return LeaseBuilder 构建器
     */
    public static Builder lease(LeaseBackend backend) {
        return new Builder(backend);
    }

    /**
     * 获取 MANAGED client。
     */
    public ManagedRetryClient client() {
        return client;
    }

    /**
     * 获取恢复处理器注册表。
     */
    public RecoveryHandlerRegistry registry() {
        return registry;
    }

    /**
     * 获取后台 worker。
     */
    public RetryLeaseWorker worker() {
        return worker;
    }

    /**
     * 启动运行时。
     */
    public synchronized void start() {
        if (started) {
            return;
        }
        // 线程名是可选装饰；未指定时沿用 RetryLeaseWorker 默认命名。
        if (workerThreadName == null || workerThreadName.trim().isEmpty()) {
            worker.start();
        } else {
            worker.start(workerThreadName);
        }
        started = true;
    }

    /**
     * 关闭运行时。
     */
    public synchronized void shutdown() {
        worker.shutdown();
        started = false;
    }

    @Override
    public void close() {
        shutdown();
    }

    /**
     * 基于 lease 的 MANAGED runtime 构建器。
     */
    @Setter
    @Accessors(fluent = true, chain = true)
    public static class Builder {

        private final LeaseBackend backend;

        private RecoveryHandlerRegistry registry;

        private RetryPolicy defaultPolicy;

        private boolean autoScanRecoveryHandlers = true;

        private LeaseWorkerPolicy workerPolicy;

        private String workerThreadName;

        Builder(LeaseBackend backend) {
            if (backend == null) {
                throw new IllegalArgumentException("LeaseBackend 不能为空");
            }
            this.backend = backend;
        }

        /**
         * 仅构建，不启动。
         */
        public ManagedRetryRuntime build() {
            RecoveryHandlerRegistry resolvedRegistry = registry == null
                    ? RecoveryHandlerRegistry.global()
                    : registry;
            if (autoScanRecoveryHandlers) {
                resolvedRegistry.autoScan();
            }

            LeaseDurableRetryStore store = new LeaseDurableRetryStore(backend);
            ManagedRetryClient client = DefaultManagedRetryClient.builder()
                    .store(store)
                    .coordinator(store)
                    .defaultPolicy(defaultPolicy)
                    .build();
            // worker 生命周期交由 runtime 包装，调用方只需要 start/shutdown runtime。
            RetryLeaseWorker worker = workerPolicy == null
                    ? new RetryLeaseWorker(backend, resolvedRegistry)
                    : new RetryLeaseWorker(backend, resolvedRegistry, workerPolicy);
            return new ManagedRetryRuntime(client, resolvedRegistry, worker, workerThreadName);
        }

        /**
         * 构建并立即启动。
         */
        public ManagedRetryRuntime start() {
            // 提供 build + start 的一键路径，给快速接入和 Spring initMethod 使用。
            ManagedRetryRuntime runtime = build();
            runtime.start();
            return runtime;
        }
    }
}
