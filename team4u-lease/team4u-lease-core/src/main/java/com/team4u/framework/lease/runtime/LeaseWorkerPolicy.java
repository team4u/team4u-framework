package com.team4u.framework.lease.runtime;

import com.team4u.framework.lease.enums.MissingHandlerStrategy;
import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

/**
 * 工作者运行策略配置类
 * <p>
 * 定义了工作者的身份标识、轮询频率、租赁时长、重试退避策略以及心跳机制等核心参数。
 * 正确的配置对于系统的吞吐量、任务实时性以及故障恢复能力至关重要。
 * 建议使用 {@link #builder()} 进行流式构建，该构造逻辑包含了基本的参数合法性校验及默认值填充。
 */
@Getter
public class LeaseWorkerPolicy {

    /**
     * Worker 的唯一身份标识，用于在后端竞争租约
     */
    private final String workerId;
    /**
     * 租赁时长（毫秒），即任务锁定的有效期
     */
    private final long leaseMillis;
    /**
     * 轮询等待时长（毫秒），当后端无任务时阻塞获取的最大时间
     */
    private final long pollWaitMillis;
    /**
     * 是否开启心跳续约机制
     */
    private final boolean heartbeatEnabled;
    /**
     * 心跳上报间隔时间（毫秒）
     */
    private final long heartbeatIntervalMillis;
    /**
     * 当任务类型找不到对应的处理器时的处理策略
     */
    private final MissingHandlerStrategy missingHandlerStrategy;

    @Builder(toBuilder = true)
    private LeaseWorkerPolicy(String workerId,
                              Long leaseMillis,
                              Long pollWaitMillis,
                              Boolean heartbeatEnabled,
                              Long heartbeatIntervalMillis,
                              MissingHandlerStrategy missingHandlerStrategy) {
        long resolvedLeaseMillis = leaseMillis == null ? 30_000L : leaseMillis;
        boolean resolvedHeartbeatEnabled = heartbeatEnabled == null || heartbeatEnabled;
        long resolvedHeartbeatIntervalMillis = heartbeatIntervalMillis == null
                ? Math.max(1L, resolvedLeaseMillis / 3L)
                : heartbeatIntervalMillis;

        this.workerId = (workerId == null || workerId.trim().isEmpty())
                ? "lease-worker-" + UUID.randomUUID().toString().replace("-", "")
                : workerId;
        this.leaseMillis = resolvedLeaseMillis;
        this.pollWaitMillis = pollWaitMillis == null ? 1_000L : pollWaitMillis;
        this.heartbeatEnabled = resolvedHeartbeatEnabled;
        this.heartbeatIntervalMillis = resolvedHeartbeatIntervalMillis;
        this.missingHandlerStrategy = missingHandlerStrategy == null ? MissingHandlerStrategy.FAIL_FAST
                : missingHandlerStrategy;

        if (this.leaseMillis <= 0L) {
            throw new IllegalArgumentException("leaseMillis must be greater than 0");
        }
        if (this.pollWaitMillis < 0L) {
            throw new IllegalArgumentException("pollWaitMillis must be greater than or equal to 0");
        }
        if (this.heartbeatIntervalMillis <= 0L) {
            throw new IllegalArgumentException("heartbeatIntervalMillis must be greater than 0");
        }
        if (this.heartbeatEnabled && this.heartbeatIntervalMillis >= this.leaseMillis) {
            throw new IllegalArgumentException(
                    "heartbeatIntervalMillis must be less than leaseMillis when heartbeat is enabled");
        }
    }
}
