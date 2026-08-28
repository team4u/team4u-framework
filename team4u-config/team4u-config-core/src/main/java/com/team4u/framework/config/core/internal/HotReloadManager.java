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
import java.util.function.Supplier;

/**
 * Aggregates and commits a reload on behalf of a manager-owned lifecycle.
 */
interface ReloadCommitter {
    boolean commitReload(long generation, ConfigSnapshot newSnapshot);
}

public class HotReloadManager {

    private static final Logger log = LoggerFactory.getLogger(HotReloadManager.class);

    private final Supplier<List<ConfigSource>> configSourcesSupplier;
    private final SnapshotAggregator aggregator;
    private final ReloadCommitter committer;
    private final ScheduledExecutorService debounceExecutor;
    private final AtomicLong versionGenerator;
    private final Object reloadExecutionMonitor = new Object();
    private volatile long debounceWindowMs;
    private long reloadToken;
    private long pendingReloadToken;
    private ScheduledFuture<?> pendingReloadTask;
    private boolean acceptingReloads = true;

    public synchronized void setDebounceWindowMs(long debounceWindowMs) {
        this.debounceWindowMs = debounceWindowMs;
    }

    HotReloadManager(Supplier<List<ConfigSource>> configSourcesSupplier,
                     SnapshotAggregator aggregator,
                     AtomicLong versionGenerator,
                     long debounceWindowMs,
                     ReloadCommitter committer) {
        this.configSourcesSupplier = configSourcesSupplier;
        this.aggregator = aggregator;
        this.versionGenerator = versionGenerator;
        this.debounceWindowMs = debounceWindowMs;
        this.committer = committer;
        this.debounceExecutor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "team4u-config-reload");
            t.setDaemon(true);
            return t;
        });
    }

    public void signalChange() {
        long token;
        long delay;

        synchronized (this) {
            if (!acceptingReloads) {
                return;
            }
            token = ++reloadToken;
            delay = debounceWindowMs;
        }

        if (delay <= 0) {
            runReload(token);
            return;
        }

        scheduleReload(token, delay);
    }

    private void scheduleReload(long token, long delay) {
        ScheduledFuture<?> task;
        synchronized (this) {
            if (!acceptingReloads || token != reloadToken) {
                return;
            }

            cancelPendingTaskLocked();
            pendingReloadToken = token;
            // schedule() only touches the executor and can safely be called under this monitor.
            task = debounceExecutor.schedule(() -> runReload(token), delay, TimeUnit.MILLISECONDS);
            pendingReloadTask = task;
        }
    }

    private synchronized void cancelPendingTaskLocked() {
        if (pendingReloadTask != null) {
            pendingReloadTask.cancel(false);
            pendingReloadTask = null;
        }
        pendingReloadToken = 0L;
    }

    public void cancelPendingReload() {
        synchronized (this) {
            reloadToken++;
            acceptingReloads = false;
            cancelPendingTaskLocked();
        }
    }

    public synchronized void resumeAcceptingReloads() {
        reloadToken++;
        acceptingReloads = true;
        cancelPendingTaskLocked();
    }

    public synchronized boolean isReloadCurrent(long token) {
        return acceptingReloads && token == reloadToken;
    }

    private void runReload(long token) {
        synchronized (reloadExecutionMonitor) {
            try {
                if (!isReloadCurrent(token)) {
                    return;
                }

                long nextVersion = versionGenerator.incrementAndGet();
                ConfigSnapshot newSnapshot = aggregator.aggregate(configSourcesSupplier.get(), nextVersion);

                if (!isReloadCurrent(token)) {
                    return;
                }

                boolean committed = committer.commitReload(token, newSnapshot);
                if (committed) {
                    log.info("Config reloaded to version {}", newSnapshot.getVersion());
                }
            } catch (Exception e) {
                log.error("Failed to hot reload configuration. Keeping the current snapshot.", e);
            } finally {
                synchronized (this) {
                    if (token == pendingReloadToken) {
                        pendingReloadToken = 0L;
                        pendingReloadTask = null;
                    }
                }
            }
        }
    }

    public void destroy() {
        cancelPendingReload();
        debounceExecutor.shutdownNow();
    }

    public static class ReloadEvent {
        public final ConfigSnapshot oldSnapshot;
        public final ConfigSnapshot newSnapshot;

        public ReloadEvent(ConfigSnapshot oldSnapshot, ConfigSnapshot newSnapshot) {
            this.oldSnapshot = oldSnapshot;
            this.newSnapshot = newSnapshot;
        }
    }
}
