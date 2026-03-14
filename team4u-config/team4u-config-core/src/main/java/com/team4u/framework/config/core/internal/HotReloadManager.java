package com.team4u.framework.config.core.internal;

import com.team4u.framework.config.core.domain.ConfigSnapshot;
import com.team4u.framework.config.core.spi.ConfigSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 配置热加载管理器
 * <p>
 * 核心功能：
 * <ul>
 * <li>收集来自各配置源的变更信号</li>
 * <li>执行基于时间窗口的防抖处理，防止频繁重载导致的资源浪费</li>
 * <li>执行快照的原子替换并触发后置事件回调</li>
 * </ul>
 * </p>
 */
public class HotReloadManager {

    private static final Logger log = LoggerFactory.getLogger(HotReloadManager.class);

    /**
     * 对外部快照引用的持有
     */
    private final AtomicReference<ConfigSnapshot> currentSnapshot;
    /**
     * 数据源列表提供者
     */
    private final Supplier<List<ConfigSource>> configSourcesSupplier;
    /**
     * 快照聚合器
     */
    private final SnapshotAggregator aggregator;
    /**
     * 成功完成重载后的回调函数
     */
    private final Consumer<ReloadEvent> onReloadSuccess;

    /**
     * 负责防抖调度的单线程执行器
     */
    private final ScheduledExecutorService debounceExecutor;
    /**
     * 版本生成器
     */
    private final AtomicLong versionGenerator;
    /**
     * 防抖延迟时间（毫秒）
     */
    private long debounceWindowMs;
    /**
     * 当前待执行的加载任务句柄
     */
    private ScheduledFuture<?> pendingTask;

    public HotReloadManager(AtomicReference<ConfigSnapshot> currentSnapshot,
                            Supplier<List<ConfigSource>> configSourcesSupplier,
                            SnapshotAggregator aggregator,
                            AtomicLong versionGenerator,
                            long debounceWindowMs,
                            Consumer<ReloadEvent> onReloadSuccess) {
        this.currentSnapshot = currentSnapshot;
        this.configSourcesSupplier = configSourcesSupplier;
        this.aggregator = aggregator;
        this.versionGenerator = versionGenerator;
        this.debounceWindowMs = debounceWindowMs;
        this.onReloadSuccess = onReloadSuccess;

        this.debounceExecutor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "team4u-config-reload");
            t.setDaemon(true);
            return t;
        });
    }

    public void setDebounceWindowMs(long debounceWindowMs) {
        this.debounceWindowMs = debounceWindowMs;
    }

    /**
     * 接收变更信号并触发重载逻辑
     * <p>
     * 当 {@code debounceWindowMs <= 0} 时，直接在当前线程同步执行重载，适用于单元测试场景，
     * 无需任何等待即可验证热重载结果。
     * 当 {@code debounceWindowMs > 0} 时，在延迟窗口内多次接收到信号则取消旧任务并重新计时，
     * 从而在高频变更场景下避免资源浪费。
     * </p>
     */
    public synchronized void signalChange() {
        // 防抖窗口为 0 或负数时，旁路调度器，直接同步执行，供测试环境使用
        if (debounceWindowMs <= 0) {
            doReload();
            return;
        }

        if (pendingTask != null && !pendingTask.isDone()) {
            pendingTask.cancel(false);
        }

        pendingTask = debounceExecutor.schedule(this::doReload, debounceWindowMs, TimeUnit.MILLISECONDS);
    }

    public synchronized void cancelPendingReload() {
        if (pendingTask != null && !pendingTask.isDone()) {
            pendingTask.cancel(false);
        }
        pendingTask = null;
    }

    /**
     * 实际执行重新加载与引用切换
     */
    private void doReload() {
        try {
            long nextVersion = versionGenerator.incrementAndGet();
            ConfigSnapshot newSnapshot = aggregator.aggregate(configSourcesSupplier.get(), nextVersion);

            // 原子替换当前生效的快照
            ConfigSnapshot oldSnapshot = currentSnapshot.getAndSet(newSnapshot);

            log.info("Config reloaded. Version bumped from {} to {}",
                    oldSnapshot != null ? oldSnapshot.getVersion() : "none",
                    nextVersion);

            // 通知外部重载成功
            if (onReloadSuccess != null) {
                onReloadSuccess.accept(new ReloadEvent(oldSnapshot, newSnapshot));
            }

        } catch (Exception e) {
            // 重载失败时打印日志并保持当前状态，确保系统可用性
            log.error("Failed to hot reload configuration. Keeping the old snapshot to maintain stability.", e);
        }
    }

    /**
     * 关闭资源，停止任务调度
     */
    public void destroy() {
        cancelPendingReload();
        debounceExecutor.shutdownNow();
    }

    /**
     * 重载成功事件载体
     */
    public static class ReloadEvent {
        public final ConfigSnapshot oldSnapshot;
        public final ConfigSnapshot newSnapshot;

        public ReloadEvent(ConfigSnapshot oldSnapshot, ConfigSnapshot newSnapshot) {
            this.oldSnapshot = oldSnapshot;
            this.newSnapshot = newSnapshot;
        }
    }
}
