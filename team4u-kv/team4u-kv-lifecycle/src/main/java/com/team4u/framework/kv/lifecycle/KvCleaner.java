package com.team4u.framework.kv.lifecycle;

import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.NativeTtlCapable;
import com.team4u.framework.kv.ScanCapable;
import com.team4u.framework.kv.lock.KvLock;
import com.team4u.framework.kv.lock.KvLockManager;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 过期清理器：周期清理不支持原生 TTL 的存储中的已过期数据
 * <p>
 * 惰性过期使清理从「必须」降级为「止血」——读取路径永不返回过期数据，
 * 清理器只负责回收存储空间（写多读少的冷键）。
 * 实现 {@link NativeTtlCapable} 的存储（如 Redis）自动跳过。
 * </p>
 * <p>
 * 可选传入 {@link KvLockManager}：多实例部署且存储共享时，全局加锁保证
 * 同一时刻仅一个实例执行清理（锁 fencing 安全，实例崩溃后租约自动释放）。
 * 不传则各实例独立清理（幂等操作，可接受）。
 * </p>
 *
 * @author jay.wu
 */
@Slf4j
public class KvCleaner implements AutoCloseable {


    private final List<KvStore> stores = new CopyOnWriteArrayList<>();
    private final long intervalMillis;
    private final int maxBatchSize;
    private final KvLockManager lockManager;
    private final String lockName;
    private volatile boolean running = true;
    private final Thread cleaner;

    public KvCleaner(long intervalMillis, int maxBatchSize) {
        this(null, intervalMillis, maxBatchSize, null);
    }

    public KvCleaner(String space, long intervalMillis, int maxBatchSize,
                     KvLockManager lockManager) {
        if (intervalMillis <= 0 || maxBatchSize <= 0) {
            throw new IllegalArgumentException(
                    "intervalMillis and maxBatchSize must be positive");
        }
        this.intervalMillis = intervalMillis;
        this.maxBatchSize = maxBatchSize;
        this.lockManager = lockManager;
        this.lockName = space == null ? "kv.cleaner" : space + ".cleaner";
        this.cleaner = new Thread(this::cleanLoop, "kv-cleaner");
        this.cleaner.setDaemon(true);
        this.cleaner.start();
    }

    /**
     * 注册待清理的存储（须实现 {@link ScanCapable}）
     */
    public KvCleaner addStore(KvStore store) {
        if (!(store instanceof ScanCapable)) {
            throw new IllegalArgumentException(
                    "KvCleaner requires a ScanCapable store, got: " + store.getClass().getName());
        }
        stores.add(store);
        return this;
    }

    private void cleanLoop() {
        while (running) {
            try {
                Thread.sleep(intervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!running) {
                return;
            }
            try {
                runOnceQuietly();
            } catch (RuntimeException e) {
                log.warn("Clean run failed", e);
            }
        }
    }

    /**
     * 执行单轮清理：全局锁保护（可选）→ 逐存储逐键空间 pruneExpired
     */
    public void runOnceQuietly() {
        if (lockManager != null) {
            // 锁租约 = 2×间隔：清理线程异常退出后锁自动过期，不会锁死
            try (KvLock ignored = lockManager.tryAcquire(lockName, intervalMillis * 2)) {
                if (ignored == null) {
                    log.debug("Other instance is cleaning, skip");
                    return;
                }
                doClean();
            }
        } else {
            doClean();
        }
    }

    private void doClean() {
        for (KvStore store : stores) {
            if (store instanceof NativeTtlCapable) {
                continue; // 原生 TTL 存储自行淘汰
            }
            ScanCapable scannable = (ScanCapable) store;
            for (String space : registeredSpaces) {
                try {
                    int count = scannable.pruneExpired(space, maxBatchSize);
                    if (count > 0) {
                        log.info("Pruned expired records|store={}|space={}|count={}",
                                store.getClass().getSimpleName(), space, count);
                    }
                } catch (RuntimeException e) {
                    log.warn("Prune failed|space={}", space, e);
                }
            }
        }
    }

    private final List<String> registeredSpaces = new CopyOnWriteArrayList<>();

    /**
     * 注册待清理的键空间（必填）：清理器按注册的键空间逐一 pruneExpired
     *
     * @return this
     */
    public KvCleaner addSpace(String space) {
        Objects.requireNonNull(space, "space");
        registeredSpaces.add(space);
        return this;
    }

    @Override
    public void close() {
        running = false;
        cleaner.interrupt();
        stores.clear();
    }
}
