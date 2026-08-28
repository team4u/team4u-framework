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
import java.util.concurrent.atomic.AtomicBoolean;
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
    private volatile long debounceWindowMs;
    private final AtomicBoolean pendingTaskAssigned = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> pendingTask;
    private long generation;
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

    /**
     * The signal path does not retain this monitor while entering manager lifecycle code.
     */
    public void signalChange() {
        long reloadGeneration;
        long delay;
        synchronized (this) {
            if (!acceptingReloads) {
                return;
            }
            reloadGeneration = generation;
            delay = debounceWindowMs;
        }

        if (delay <= 0) {
            doReload(reloadGeneration);
            return;
        }

        scheduleDebouncedReload(reloadGeneration, delay);
    }

    private void scheduleDebouncedReload(long reloadGeneration, long delay) {
        // Manager lifecycle methods may cancel synchronously; never schedule under this monitor.
        synchronized (this) {
            if (pendingTaskAssigned.get()) {
                return;
            }
            pendingTaskAssigned.set(true);
        }

        ScheduledFuture<?> task = debounceExecutor.schedule(
                () -> doReload(reloadGeneration), delay, TimeUnit.MILLISECONDS);
        pendingTask = task;
    }

    public void cancelPendingReload() {
        synchronized (this) {
            generation++;
            acceptingReloads = false;
        }
        ScheduledFuture<?> task = pendingTask;
        pendingTask = null;
        pendingTaskAssigned.set(false);
        if (task != null) {
            task.cancel(false);
        }
    }

    public synchronized void resumeAcceptingReloads() {
        generation++;
        acceptingReloads = true;
    }

    public synchronized boolean isReloadCurrent(long reloadGeneration) {
        return acceptingReloads && reloadGeneration == generation;
    }

    private void doReload(long reloadGeneration) {
        try {
            synchronized (this) {
                if (!acceptingReloads || reloadGeneration != generation) {
                    return;
                }
            }

            long nextVersion = versionGenerator.incrementAndGet();
            ConfigSnapshot newSnapshot = aggregator.aggregate(configSourcesSupplier.get(), nextVersion);

            synchronized (this) {
                if (!acceptingReloads || reloadGeneration != generation) {
                    return;
                }
            }

            if (committer.commitReload(reloadGeneration, newSnapshot)) {
                log.info("Config reloaded to version {}", newSnapshot.getVersion());
            }
        } catch (Exception e) {
            log.error("Failed to hot reload configuration. Keeping the current snapshot.", e);
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
