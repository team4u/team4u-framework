package com.team4u.framework.base.lease;

import com.team4u.framework.base.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于纯 JDK 调度器的租约心跳器（统一线程模型）
 * <p>
 * 持有者按固定间隔对「持有者令牌」执行续约操作，维持租约不过期。
 * 本类治理了此前两套私有心跳实现的线程模型差异：
 * </p>
 * <ul>
 *     <li><b>线程模型</b>：守护线程的 {@code ScheduledExecutorService}（命名
 *     team4u-heartbeat-N）+ {@code scheduleAtFixedRate}，对齐 TaskWorker 的
 *     HeartbeatTask 模型；不再使用 KvLockManager 的 wait/notify 专用心跳线程，
 *     一个心跳器一个轻量调度线程，关停即回收</li>
 *     <li><b>失败即弃权</b>：续约返回 false 视为租约丢失（被接管/过期），
 *     立即停止后续心跳并触发 onLost 回调（对齐 KvLockManager 的 renew 失败
 *     即 removeHeld）；这是「持有者已无权续期，继续心跳只会刷他人的租约」
 *     的安全停止，持有方应停止临界区工作</li>
 *     <li><b>异常容忍</b>：续约抛出的异常只记 warn 不停止心跳——瞬时故障不应
 *     弃权，心跳间隔（默认 lease/3）已预留两个失败窗口；
 *     但 {@link Error} 会上抛并停止心跳（调度器语义：任务抛异常静默取消后续，
 *     本类选择显式停止避免「假活着」）</li>
 *     <li><b>默认间隔</b>：leaseMillis / 3（业界惯例），可经构造器调整；
 *     要求大于 0 且严格小于租约时长</li>
 * </ul>
 * <p>
 * 典型用法：
 * </p>
 * <pre>{@code
 * ScheduledHeartbeat heartbeat = ScheduledHeartbeat.builder()
 *         .token(holderToken)
 *         .leaseMillis(30_000L)
 *         .onLost(() -> abortCriticalSection())
 *         .operation(token -> backend.renew(token))
 *         .build();
 * heartbeat.start();
 * ...
 * heartbeat.stop(); // 释放持有权时主动停跳，幂等
 * }</pre>
 * <p>
 * 本类线程安全：start/stop 可从任意线程调用；onLost 回调在心跳调度线程执行，
 * 应快速返回且不得抛异常（抛出仅记日志）。一个实例只对应一段持有期，
 * 丢失（onLost 触发）或 stop 后不可复用，需重新 build。
 * </p>
 *
 * @author jay.wu
 */
public final class ScheduledHeartbeat implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ScheduledHeartbeat.class);

    private final String token;
    private final long leaseMillis;
    private final long intervalMillis;
    private final HeartbeatOperation operation;
    private final Runnable onLost;
    private final ScheduledExecutorService ownScheduler;

    /**
     * 是否已启动（含已停止），保证 start 只生效一次
     */
    private final AtomicBoolean started = new AtomicBoolean(false);
    /**
     * 是否已终止（stop 或丢失），保证 stop/onLost 幂等
     */
    private final AtomicBoolean terminated = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> future;

    private ScheduledHeartbeat(Builder builder) {
        this.token = builder.token;
        this.leaseMillis = builder.leaseMillis;
        this.intervalMillis = builder.resolvedIntervalMillis();
        this.operation = builder.operation;
        this.onLost = builder.onLost;
        this.ownScheduler = HeartbeatThreads.newSingleThreadScheduler(token);
    }

    /**
     * 创建构建器
     *
     * @param token     持有者令牌（传递给续约操作，用于校验「续的是自己的约」）
     * @param leaseMillis 租约时长（毫秒，必须大于 0）
     * @param operation 续约操作
     * @return 构建器
     */
    public static Builder builder(String token, long leaseMillis, HeartbeatOperation operation) {
        return new Builder(token, leaseMillis, operation);
    }

    /**
     * 启动心跳（幂等：重复调用返回自身，不重复调度）
     *
     * @return this
     * @throws IllegalStateException 已 stop 或已丢失后重启（一个实例只对应一段持有期），
     *                             或内部调度器已关闭导致排程被拒绝（如与 stop 并发触发）
     */
    public ScheduledHeartbeat start() {
        if (terminated.get()) {
            // 已 stop 或已丢失：实例已终结，拒绝重启（一个实例只对应一段持有期）
            throw new IllegalStateException("ScheduledHeartbeat cannot be started after stop/lost");
        }
        if (!started.compareAndSet(false, true)) {
            // 已启动过：幂等返回（首次 start 生效）
            return this;
        }
        try {
            future = ownScheduler.scheduleAtFixedRate(this::heartbeatOnce,
                    intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            terminated.set(true);
            HeartbeatThreads.shutdownQuietly(ownScheduler);
            throw new IllegalStateException("ScheduledHeartbeat scheduling rejected", e);
        }
        return this;
    }

    /**
     * 停止心跳（幂等）
     * <p>
     * 取消后续调度并关闭调度线程。不执行任何续约/释放补偿——
     * 主动 stop 后租约自然过期即可，无需续期他人的约。
     * 不触发 onLost（onLost 仅在「续约失败被动丢失」时触发）。
     * </p>
     */
    public void stop() {
        if (!terminated.compareAndSet(false, true)) {
            return;
        }
        cancelFuture();
        HeartbeatThreads.shutdownQuietly(ownScheduler);
    }

    /**
     * {@link #stop()} 的 AutoCloseable 别名
     */
    @Override
    public void close() {
        stop();
    }

    /**
     * 心跳是否仍在运行
     *
     * @return {@code true} 表示已启动且未停止、未丢失
     */
    public boolean isRunning() {
        return started.get() && !terminated.get();
    }

    /**
     * 持有者令牌
     *
     * @return 构造时传入的 token
     */
    public String getToken() {
        return token;
    }

    /**
     * 租约时长（毫秒）
     *
     * @return 构造时传入的 leaseMillis
     */
    public long getLeaseMillis() {
        return leaseMillis;
    }

    /**
     * 实际生效的心跳间隔（毫秒）
     *
     * @return 显式配置值或默认的 leaseMillis/3
     */
    public long getIntervalMillis() {
        return intervalMillis;
    }

    private void heartbeatOnce() {
        if (terminated.get()) {
            return;
        }
        boolean renewed;
        try {
            renewed = operation.renew(token);
        } catch (Exception e) {
            // 瞬时故障容忍：只记 warn，不弃权（间隔已预留失败窗口）
            log.warn("ScheduledHeartbeat renew error, keep trying|token={}", token, e);
            return;
        }
        if (!renewed) {
            log.warn("ScheduledHeartbeat lost, stop beating|token={}", token);
            terminateByLoss();
        }
    }

    /**
     * 因续约失败而终止：与 stop 竞争幂等，胜者执行 onLost 回调
     */
    private void terminateByLoss() {
        if (!terminated.compareAndSet(false, true)) {
            return;
        }
        cancelFuture();
        HeartbeatThreads.shutdownQuietly(ownScheduler);
        if (onLost != null) {
            try {
                onLost.run();
            } catch (Throwable t) {
                log.warn("ScheduledHeartbeat onLost callback failed|token={}", token, t);
            }
        }
    }

    private void cancelFuture() {
        ScheduledFuture<?> current = future;
        if (current != null) {
            current.cancel(false);
        }
    }

    /**
     * 续约操作
     * <p>
     * 实现方以持有者令牌向租约后端发起续约，并返回续约是否成功。
     * 返回 {@code false} 表示租约已丢失（过期被接管、令牌不匹配等），
     * 心跳器将立即停止并触发 onLost 回调。抛出的异常视为瞬时故障，
     * 心跳器记录后继续按间隔重试。
     * </p>
     *
     * @author jay.wu
     */
    @FunctionalInterface
    public interface HeartbeatOperation {

        /**
         * 续约
         *
         * @param token 持有者令牌
         * @return {@code true} 续约成功；{@code false} 租约已丢失
         * @throws Exception 续约途中的瞬时故障（网络抖动等），心跳器将重试
         */
        boolean renew(String token) throws Exception;
    }

    /**
     * 构建器
     *
     * @author jay.wu
     */
    public static final class Builder {

        private final String token;
        private final long leaseMillis;
        private final HeartbeatOperation operation;
        private Long intervalMillis;
        private Runnable onLost;

        private Builder(String token, long leaseMillis, HeartbeatOperation operation) {
            if (StringUtil.isBlank(token)) {
                throw new IllegalArgumentException("token must not be blank");
            }
            if (leaseMillis <= 0L) {
                throw new IllegalArgumentException("leaseMillis must be positive: " + leaseMillis);
            }
            Objects.requireNonNull(operation, "operation");
            this.token = token;
            this.leaseMillis = leaseMillis;
            this.operation = operation;
        }

        /**
         * 设置心跳间隔（默认 leaseMillis / 3）
         *
         * @param intervalMillis 心跳间隔（毫秒），必须大于 0 且严格小于 leaseMillis
         * @return this
         */
        public Builder intervalMillis(long intervalMillis) {
            this.intervalMillis = Long.valueOf(intervalMillis);
            return this;
        }

        /**
         * 设置丢失回调：续约失败（返回 false）自动停止心跳后异步触发一次
         *
         * @param onLost 丢失回调，可为 null（仅停止心跳不通知）
         * @return this
         */
        public Builder onLost(Runnable onLost) {
            this.onLost = onLost;
            return this;
        }

        /**
         * 校验并构造心跳器（未启动，需显式 {@link ScheduledHeartbeat#start()}）
         *
         * @return ScheduledHeartbeat 实例
         * @throws IllegalArgumentException token 为空白、leaseMillis 非正数、
         *                                  intervalMillis 非正数或不小于 leaseMillis
         */
        public ScheduledHeartbeat build() {
            long interval = resolvedIntervalMillis();
            if (interval <= 0L || interval >= leaseMillis) {
                throw new IllegalArgumentException(
                        "intervalMillis must be positive and less than leaseMillis: "
                                + interval + " vs leaseMillis=" + leaseMillis);
            }
            return new ScheduledHeartbeat(this);
        }

        private long resolvedIntervalMillis() {
            return intervalMillis != null ? intervalMillis.longValue() : leaseMillis / 3L;
        }
    }
}
