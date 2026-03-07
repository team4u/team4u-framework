package com.team4u.framework.lease;

import com.team4u.framework.lease.backoff.Backoff;
import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

/**
 * Worker 运行策略配置类。
 * <p>
 * 定义了 Worker 的身份标识、轮询频率、租赁时长、重试退避策略以及心跳机制等关键运行参数。
 * 建议使用 {@link #builder()} 进行流式构建。
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
     * 最大重试次数（-1 表示无限制）
     */
    private final int maxAttempts;
    /**
     * 退避算法策略，用于计算重试延迟
     */
    private final Backoff backoff;
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
                              Integer maxAttempts,
                              Backoff backoff,
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
        this.maxAttempts = maxAttempts == null ? 8 : maxAttempts;
        this.backoff = backoff == null ? Backoff.fixed(1_000L) : backoff;
        this.heartbeatEnabled = resolvedHeartbeatEnabled;
        this.heartbeatIntervalMillis = resolvedHeartbeatIntervalMillis;
        this.missingHandlerStrategy = missingHandlerStrategy == null ? MissingHandlerStrategy.FAIL_FAST : missingHandlerStrategy;

        if (this.leaseMillis <= 0L) {
            throw new IllegalArgumentException("leaseMillis must be greater than 0");
        }
        if (this.pollWaitMillis < 0L) {
            throw new IllegalArgumentException("pollWaitMillis must be greater than or equal to 0");
        }
        if (this.maxAttempts == 0 || this.maxAttempts < -1) {
            throw new IllegalArgumentException("maxAttempts must be greater than 0 or -1");
        }
        if (this.heartbeatIntervalMillis <= 0L) {
            throw new IllegalArgumentException("heartbeatIntervalMillis must be greater than 0");
        }
        if (this.heartbeatEnabled && this.heartbeatIntervalMillis >= this.leaseMillis) {
            throw new IllegalArgumentException("heartbeatIntervalMillis must be less than leaseMillis when heartbeat is enabled");
        }
    }

    /**
     * 判断是否满足重试条件
     *
     * @param attemptCount 已尝试次数
     * @return 允许重试返回 true
     */
    public boolean shouldRetry(int attemptCount) {
        return maxAttempts == -1 || attemptCount < maxAttempts;
    }

    /**
     * 计算下次尝试前的延迟时间
     *
     * @param attemptCount 当前已尝试次数
     * @return 延迟毫秒数
     */
    public long nextDelayMillis(int attemptCount) {
        return backoff.calculateMillis(attemptCount);
    }
}
