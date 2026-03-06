package com.team4u.framework.retry.lease;

import com.team4u.framework.lease.LeaseBackend;
import com.team4u.framework.lease.LeaseWorker;
import com.team4u.framework.lease.LeaseWorkerPolicy;
import com.team4u.framework.retry.recovery.RecoveryHandlerRegistry;

/**
 * 基于 team4u-lease 的重试恢复工作者。
 * <p>
 * 该类通过集成 {@link LeaseWorker}，实现了分布式环境下的重试任务恢复。
 * 它能够从 {@link LeaseBackend} 拉取超时的重试任务，并交由对应的 {@link RecoveryHandlerRegistry}
 * 进行补偿处理。
 */
public class RetryLeaseWorker extends LeaseWorker {

    /**
     * 使用默认的恢复处理器注册表和策略创建工作者
     *
     * @param backend 租约/重试存储后端
     */
    public RetryLeaseWorker(LeaseBackend backend) {
        this(backend, RecoveryHandlerRegistry.global(), LeaseWorkerPolicy.builder().build());
    }

    /**
     * 使用指定的恢复处理器注册表创建工作者
     *
     * @param backend  租约/重试存储后端
     * @param registry 恢复处理器注册表
     */
    public RetryLeaseWorker(LeaseBackend backend, RecoveryHandlerRegistry registry) {
        this(backend, registry, LeaseWorkerPolicy.builder().build());
    }

    /**
     * 全参构造函数，允许完全自定义行为
     *
     * @param backend  租约/重试存储后端
     * @param registry 恢复处理器注册表
     * @param policy   租约工作策略
     */
    public RetryLeaseWorker(LeaseBackend backend, RecoveryHandlerRegistry registry, LeaseWorkerPolicy policy) {
        super(backend, new RecoveryHandlerRegistryLeaseAdapter(registry), policy);
    }
}
