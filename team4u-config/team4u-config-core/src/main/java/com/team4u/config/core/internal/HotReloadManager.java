package com.team4u.config.core.internal;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import com.team4u.config.core.domain.ConfigSnapshot;
import com.team4u.config.core.spi.ConfigSource;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 负责收集变更信号，执行防抖，并原子更新 Snapshot
 */
public class HotReloadManager {

    private static final Log log = LogFactory.get();

    private final AtomicReference<ConfigSnapshot> currentSnapshot;
    private final List<ConfigSource> configSources;
    private final SnapshotAggregator aggregator;
    private final Consumer<ReloadEvent> onReloadSuccess;

    private final ScheduledExecutorService debounceExecutor;
    private final long debounceWindowMs;
    private final AtomicLong versionGenerator = new AtomicLong(System.currentTimeMillis());
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
     * 接收变更信号，采用防抖机制拦截密集变更
     */
    public synchronized void signalChange() {
        if (pendingTask != null && !pendingTask.isDone()) {
            pendingTask.cancel(false);
        }

        pendingTask = debounceExecutor.schedule(this::doReload, debounceWindowMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 实际执行重新加载与覆盖
     */
    private void doReload() {
        try {
            long nextVersion = versionGenerator.incrementAndGet();
            ConfigSnapshot newSnapshot = aggregator.aggregate(configSources, nextVersion);

            // 原子替换当前快照
            ConfigSnapshot oldSnapshot = currentSnapshot.getAndSet(newSnapshot);

            log.info("Config reloaded. Version bumped from {} to {}",
                    oldSnapshot != null ? oldSnapshot.getVersion() : "none",
                    nextVersion);

            // 触发事件回调
            if (onReloadSuccess != null) {
                onReloadSuccess.accept(new ReloadEvent(oldSnapshot, newSnapshot));
            }

        } catch (Exception e) {
            log.error(e, "Failed to hot reload configuration. Keeping the old snapshot to maintain stability.");
        }
    }

    /**
     * 关闭销毁调度器
     */
    public void destroy() {
        debounceExecutor.shutdownNow();
    }

    /**
     * 重载事件数据包
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
