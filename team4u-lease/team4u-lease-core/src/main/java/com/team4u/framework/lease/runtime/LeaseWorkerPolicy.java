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
     * 工作者（Worker）的唯一身份标识
     * <p>
     * 在分布式系统中，用于标识当前持有租约的节点，通常为 IP+PID 或 UUID。
     */
    private final String workerId;

    /**
     * 默认租约时长（毫秒）
     * <p>
     * 抢占成功后任务锁定的有效期。业务执行必须在此时间内完成或持续进行心跳续约。
     */
    private final long leaseMillis;

    /**
     * 轮询等待时长（毫秒）
     * <p>
     * 当后端队列为空时，Worker 阻塞式拉取任务的最大等待时间（Long Polling）。
     */
    private final long pollWaitMillis;

    /**
     * 是否开启心跳续约机制
     * <p>
     * 若开启，Worker 会在后台自动为处理中的长时任务续期，避免租约过期。
     */
    private final boolean heartbeatEnabled;

    /**
     * 心跳自动续约的间隔时长（毫秒）
     */
    private final long heartbeatIntervalMillis;

    /**
     * 当任务类型未注册处理器（Missing Handler）时的处理策略
     */
    private final MissingHandlerStrategy missingHandlerStrategy;

    /**
     * 缺失处理器时释放任务的重试延迟（毫秒）
     * <p>
     * 在 {@link MissingHandlerStrategy#RETRY_LATER} 模式下，任务释放回队列前需要等待的延迟时长，
     * 用于灰度发布或平滑升级期间的错峰重试。默认等于 pollWaitMillis。
     */
    private final long missingHandlerRetryDelayMillis;

    @Builder(toBuilder = true)
    private LeaseWorkerPolicy(String workerId,
                              Long leaseMillis,
                              Long pollWaitMillis,
                              Boolean heartbeatEnabled,
                              Long heartbeatIntervalMillis,
                              MissingHandlerStrategy missingHandlerStrategy,
                              Long missingHandlerRetryDelayMillis) {
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
        this.missingHandlerRetryDelayMillis = missingHandlerRetryDelayMillis == null
                ? this.pollWaitMillis
                : missingHandlerRetryDelayMillis;

        if (this.leaseMillis <= 0L) {
            throw new IllegalArgumentException("leaseMillis must be greater than 0");
        }
        if (this.pollWaitMillis < 0L) {
            throw new IllegalArgumentException("pollWaitMillis must be greater than or equal to 0");
        }
        if (this.missingHandlerRetryDelayMillis < 0L) {
            throw new IllegalArgumentException("missingHandlerRetryDelayMillis must be greater than or equal to 0");
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
