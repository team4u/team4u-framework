package com.team4u.framework.config.core.internal;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import com.team4u.framework.config.core.domain.ConfigSnapshot;
import com.team4u.framework.config.core.spi.ConfigSource;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 配置热加载管理器
 * <p>
 * 核心功能：
 * <ul>
 *     <li>收集来自各配置源的变更信号</li>
 *     <li>执行基于时间窗口的防抖处理，防止频繁重载导致的资源浪费</li>
 *     <li>执行快照的原子替换并触发后置事件回调</li>
 * </ul>
 * </p>
 */
public class HotReloadManager {

    private static final Log log = LogFactory.get();

    /**
     * 对外部快照引用的持有
     */
    private final AtomicReference<ConfigSnapshot> currentSnapshot;
    /**
     * 数据源列表
     */
    private final List<ConfigSource> configSources;
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
     * 防抖延迟时间（毫秒）
     */
    private final long debounceWindowMs;
    /**
     * 版本生成器
     */
    private final AtomicLong versionGenerator = new AtomicLong(System.currentTimeMillis());
    /**
     * 当前待执行的加载任务句柄
     */
    private ScheduledFuture<?> pendingTask;

    public HotReloadManager(AtomicReference<ConfigSnapshot> currentSnapshot,
                            List<ConfigSource> configSources,
                            SnapshotAggregator aggregator,
                            long debounceWindowMs,
                            Consumer<ReloadEvent> onReloadSuccess) {
        this.currentSnapshot = currentSnapshot;
        this.configSources = configSources;
        this.aggregator = aggregator;
        this.debounceWindowMs = debounceWindowMs;
        this.onReloadSuccess = onReloadSuccess;

        this.debounceExecutor = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "team4u-config-reload");
                t.setDaemon(true);
                return t;
            }
        });
    }

    /**
     * 接收变更信号并触发防抖逻辑
     * <p>
     * 如果在延迟窗口内多次接收到信号，之前的任务将被取消，重新计时。
     * </p>
     */
    public synchronized void signalChange() {
        if (pendingTask != null && !pendingTask.isDone()) {
            pendingTask.cancel(false);
        }

        pendingTask = debounceExecutor.schedule(this::doReload, debounceWindowMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 实际执行重新加载与引用切换
     */
    private void doReload() {
        try {
            long nextVersion = versionGenerator.incrementAndGet();
            ConfigSnapshot newSnapshot = aggregator.aggregate(configSources, nextVersion);

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
            log.error(e, "Failed to hot reload configuration. Keeping the old snapshot to maintain stability.");
        }
    }

    /**
     * 关闭资源，停止任务调度
     */
    public void destroy() {
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
