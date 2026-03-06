package com.team4u.framework.lease;

import com.team4u.framework.lease.backoff.Backoff;

import java.util.UUID;

/**
 * Worker 运行策略配置类。
 * <p>
 * 定义了 Worker 的身份标识、轮询频率、租赁时长、重试退避策略以及心跳机制等关键运行参数。
 * 建议使用 {@link #builder()} 进行流式构建。
 */
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

    private LeaseWorkerPolicy(Builder builder) {
        this.workerId = builder.workerId == null || builder.workerId.trim().isEmpty()
                ? "lease-worker-" + UUID.randomUUID().toString().replace("-", "")
                : builder.workerId;
        this.leaseMillis = builder.leaseMillis;
        this.pollWaitMillis = builder.pollWaitMillis;
        this.maxAttempts = builder.maxAttempts;
        this.backoff = builder.backoff;
        this.heartbeatEnabled = builder.heartbeatEnabled;
        this.heartbeatIntervalMillis = builder.heartbeatIntervalMillis;
        this.missingHandlerStrategy = builder.missingHandlerStrategy;
    }

    /**
     * 获取策略建造者
     *
     * @return 建造者实例
     */
    public static Builder builder() {
        return new Builder();
    }

    public String getWorkerId() {
        return workerId;
    }

    public long getLeaseMillis() {
        return leaseMillis;
    }

    public long getPollWaitMillis() {
        return pollWaitMillis;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public Backoff getBackoff() {
        return backoff;
    }

    public boolean isHeartbeatEnabled() {
        return heartbeatEnabled;
    }

    public long getHeartbeatIntervalMillis() {
        return heartbeatIntervalMillis;
    }

    public MissingHandlerStrategy getMissingHandlerStrategy() {
        return missingHandlerStrategy;
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

    /**
     * LeaseWorkerPolicy 建造者
     */
    public static class Builder {
        private String workerId;
        private long leaseMillis = 30_000L;
        private long pollWaitMillis = 1_000L;
        private int maxAttempts = 8;
        private Backoff backoff = Backoff.fixed(1_000L);
        private boolean heartbeatEnabled = true;
        private long heartbeatIntervalMillis = 10_000L;
        private MissingHandlerStrategy missingHandlerStrategy = MissingHandlerStrategy.FAIL_FAST;

        /**
         * 设置 Worker ID，不设置则自动生成
         */
        public Builder workerId(String workerId) {
            this.workerId = workerId;
            return this;
        }

        /**
         * 设置租赁(锁定)时长
         */
        public Builder leaseMillis(long leaseMillis) {
            this.leaseMillis = leaseMillis;
            return this;
        }

        /**
         * 设置轮询阻塞超时时间
         */
        public Builder pollWaitMillis(long pollWaitMillis) {
            this.pollWaitMillis = pollWaitMillis;
            return this;
        }

        /**
         * 设置最大尝试次数
         */
        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        /**
         * 设置退避策略
         */
        public Builder backoff(Backoff backoff) {
            this.backoff = backoff;
            return this;
        }

        /**
         * 设置是否启用心跳续约
         */
        public Builder heartbeatEnabled(boolean heartbeatEnabled) {
            this.heartbeatEnabled = heartbeatEnabled;
            return this;
        }

        /**
         * 设置心跳上报频率
         */
        public Builder heartbeatIntervalMillis(long heartbeatIntervalMillis) {
            this.heartbeatIntervalMillis = heartbeatIntervalMillis;
            return this;
        }

        /**
         * 设置缺失处理器时的应对策略
         */
        public Builder missingHandlerStrategy(MissingHandlerStrategy missingHandlerStrategy) {
            this.missingHandlerStrategy = missingHandlerStrategy;
            return this;
        }

        /**
         * 校验并构建策略实例
         *
         * @return 策略对象
         */
        public LeaseWorkerPolicy build() {
            if (leaseMillis <= 0L) {
                throw new IllegalArgumentException("leaseMillis must be greater than 0");
            }
            if (pollWaitMillis < 0L) {
                throw new IllegalArgumentException("pollWaitMillis must be greater than or equal to 0");
            }
            if (maxAttempts == 0 || maxAttempts < -1) {
                throw new IllegalArgumentException("maxAttempts must be greater than 0 or -1");
            }
            if (backoff == null) {
                throw new IllegalArgumentException("backoff must not be null");
            }
            if (heartbeatIntervalMillis <= 0L) {
                throw new IllegalArgumentException("heartbeatIntervalMillis must be greater than 0");
            }
            if (missingHandlerStrategy == null) {
                throw new IllegalArgumentException("missingHandlerStrategy must not be null");
            }
            return new LeaseWorkerPolicy(this);
        }
    }
}
