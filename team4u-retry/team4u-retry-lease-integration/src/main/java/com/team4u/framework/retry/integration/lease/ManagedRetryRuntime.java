package com.team4u.framework.retry.integration.lease;

import com.team4u.framework.lease.api.LeaseBackend;
import com.team4u.framework.lease.runtime.LeaseWorkerPolicy;
import com.team4u.framework.retry.managed.client.DefaultManagedRetryClient;
import com.team4u.framework.retry.managed.client.ManagedRetryClient;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.managed.recovery.RecoveryHandler;
import com.team4u.framework.retry.managed.recovery.RecoveryHandlerRegistry;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * 托管重试运行时（Managed Retry Runtime）。
 * <p>
 * 该类作为集成租约系统（Lease System）的重试环境入口，负责协调持久化存储、重试策略以及后台恢复任务的调度与执行。
 * 它管理着一个 {@link ManagedRetryClient} 用于提交任务，以及一个 {@link RetryLeaseWorker}
 * 用于在后台异步处理恢复逻辑。
 * </p>
 */
public class ManagedRetryRuntime implements AutoCloseable {

    /**
     * 重试业务客户端，供业务代码提交重试任务使用
     */
    private final ManagedRetryClient client;
    /**
     * 恢复处理器注册表，管理所有的 {@link RecoveryHandler}
     */
    private final RecoveryHandlerRegistry registry;
    /**
     * 基于租约机制的后台工作轮询器
     */
    private final RetryLeaseWorker worker;
    /**
     * 工作线程名称前缀
     */
    private final String workerThreadName;
    /**
     * 运行时是否已启动的标识
     */
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
     * 创建基于指定租约后端的运行时构建器。
     *
     * @param backend 租约系统后端实现，负责底层任务的抢占与状态持久化
     * @return 运行时构建器实例
     */
    public static Builder lease(LeaseBackend backend) {
        return new Builder(backend);
    }

    /**
     * 获取托管模式重试客户端。
     */
    public ManagedRetryClient client() {
        return client;
    }

    /**
     * 获取恢复处理器注册中心。
     */
    public RecoveryHandlerRegistry registry() {
        return registry;
    }

    /**
     * 获取底层重试工作者。
     */
    public RetryLeaseWorker worker() {
        return worker;
    }

    /**
     * 启动重试运行时。
     * <p>
     * 启动后，后台工作者将开始轮询并处理已持久化且到达执行时间的重试任务。
     * </p>
     */
    public synchronized void start() {
        if (started) {
            return;
        }
        if (workerThreadName == null || workerThreadName.trim().isEmpty()) {
            worker.start();
        } else {
            worker.start(workerThreadName);
        }
        started = true;
    }

    /**
     * 停止重试运行时，关闭后台轮询工作者。
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
     * ManagedRetryRuntime 辅助构建器。
     */
    @Setter
    @Accessors(fluent = true, chain = true)
    public static class Builder {

        private final LeaseBackend backend;
        /**
         * 自定义处理器注册表，若不指定则使用全局默认注册表
         */
        private RecoveryHandlerRegistry registry;
        /**
         * 默认重试策略，在提交任务未指定策略时生效
         */
        private RetryPolicy defaultPolicy;
        /**
         * 是否自动扫描并注册 classpath 中的 RecoveryHandler，默认为 {@code true}
         */
        private boolean autoScanRecoveryHandlers = true;
        /**
         * 租约工作者运行策略（并行度、领用间隔等）
         */
        private LeaseWorkerPolicy workerPolicy;
        /**
         * 工作线程名称前缀
         */
        private String workerThreadName;

        Builder(LeaseBackend backend) {
            if (backend == null) {
                throw new IllegalArgumentException("LeaseBackend 不能为空");
            }
            this.backend = backend;
        }

        /**
         * 构建运行时实例，不自动启动。
         */
        public ManagedRetryRuntime build() {
            RecoveryHandlerRegistry resolvedRegistry = registry == null
                    ? RecoveryHandlerRegistry.global()
                    : registry;
            if (autoScanRecoveryHandlers) {
                resolvedRegistry.autoScan();
            }
            // 初始化由租约后端支撑的仓储实现
            LeaseDurableRetryStore store = new LeaseDurableRetryStore(backend);
            // 构建默认的托管客户端
            ManagedRetryClient client = DefaultManagedRetryClient.builder()
                    .store(store)
                    .dispatcher(store)
                    .defaultPolicy(defaultPolicy)
                    .build();
            // 构建驱动恢复逻辑的工作者
            RetryLeaseWorker worker = workerPolicy == null
                    ? new RetryLeaseWorker(backend, resolvedRegistry)
                    : new RetryLeaseWorker(backend, resolvedRegistry, workerPolicy);
            return new ManagedRetryRuntime(client, resolvedRegistry, worker, workerThreadName);
        }

        /**
         * 构建并立即启动运行时。
         */
        public ManagedRetryRuntime start() {
            ManagedRetryRuntime runtime = build();
            runtime.start();
            return runtime;
        }
    }
}
