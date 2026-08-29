package com.team4u.framework.kv.lock;

import com.team4u.framework.kv.CasCapable;
import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.KvStoreException;
import com.team4u.framework.kv.KvStores;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.SpaceKey;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * KV 分布式锁：持有者令牌 + 心跳续约 + fencing 安全释放
 * <p>
 * 提供三项正确性保证：
 * </p>
 * <ul>
 *     <li><b>误删防护</b>：释放经 {@link CasCapable#compareAndRemove} 仅删除
 *     自己令牌持有的锁，锁被他人接管后旧持有者的释放是安全的空操作</li>
 *     <li><b>超时误放防护</b>：默认开启后台心跳续约（间隔 = lease/3），
 *     持有方存活期间租约持续滚动；续约经 {@link CasCapable#compareAndSet}
 *     校验令牌，绝不续期他人的锁</li>
 *     <li><b>宕机自愈</b>：持有方进程崩溃后心跳停止，租约到期自动失效，
 *     其他实例可获取（过期由存储惰性判定，无需后台任务回写）</li>
 * </ul>
 * <p>
 * 互斥范围由底层存储决定：内存实现为进程内互斥；数据库/Redis 等共享存储为跨实例互斥。
 * 底层存储必须实现 {@link CasCapable}，否则构造期快速失败。
 * 装饰链自动解析——可传入 ObservedStore/TieredStore 等装饰过的存储，
 * 构造期经 {@link KvStores#capabilityOf} 沿装饰链找到 CasCapable 存储；
 * 锁操作直达解析后的底层存储（不经过缓存/观测装饰层），避免缓存层让续约读到陈旧令牌。
 * 适合「尽量互斥」场景（任务防重、缓存刷新防击穿）；
 * 高精度互斥（金融扣减等）请结合业务幂等或专业锁组件。
 * </p>
 *
 * @author jay.wu
 */
@Slf4j
public class KvLockManager implements AutoCloseable {


    private final KvStore store;
    private final CasCapable casStore;
    private final Clock clock;
    private final Config config;
    private final String ownerId;

    private final ConcurrentHashMap<String, Set<HeldLock>> heldLocks = new ConcurrentHashMap<>();
    private volatile boolean running = true;
    private final Object heartbeatSignal = new Object();
    private final Thread heartbeatThread;

    public KvLockManager(KvStore store) {
        this(store, Clock.systemUTC(), new Config());
    }

    public KvLockManager(KvStore store, Clock clock, Config config) {
        CasCapable resolved = KvStores.capabilityOf(store, CasCapable.class);
        if (resolved == null) {
            throw new KvStoreException("Lock requires a CasCapable store (through decorator chains), got: "
                    + store.getClass().getName());
        }
        // 锁的全部操作（get/put/remove/expire/CAS）直达解析后的底层存储：
        // 若经缓存装饰层续约，读到陈旧令牌会破坏续约正确性
        this.store = (KvStore) resolved;
        this.casStore = resolved;
        this.clock = clock;
        this.config = Objects.requireNonNull(config, "config");
        this.config.validate();
        this.ownerId = config.getOwnerId() != null ? config.getOwnerId()
                : "worker-" + UUID.randomUUID().toString().substring(0, 8);

        this.heartbeatThread = new Thread(this::heartbeatLoop, "kv-lock-heartbeat-" + ownerId);
        this.heartbeatThread.setDaemon(true);
        this.heartbeatThread.start();
    }

    /**
     * 尝试获取锁，立即返回
     *
     * @return 锁句柄；被他人持有（或已过期未释放竞争失败）返回 {@code null}
     */
    public KvLock tryAcquire(String name, long leaseMillis) {
        Objects.requireNonNull(name, "name");
        if (leaseMillis <= 0) {
            // 永不过期的锁在进程崩溃后无法自愈，直接拒绝
            throw new IllegalArgumentException("leaseMillis must be positive: " + leaseMillis);
        }
        checkRunning();
        String token = ownerId + ":" + UUID.randomUUID();

        boolean acquired = store.put(
                lockKey(name),
                KvRecord.of(token, leaseMillis, clock.millis()),
                PutMode.IF_ABSENT);

        if (!acquired) {
            return null;
        }
        HeldLock held = new HeldLock(name, token, leaseMillis);
        heldLocks.computeIfAbsent(name, ignored -> ConcurrentHashMap.newKeySet()).add(held);
        synchronized (heartbeatSignal) {
            heartbeatSignal.notifyAll();
        }
        return new KvLock(this, held);
    }

    /**
     * 获取锁，阻塞直至成功或超时
     * <p>
     * 超时基于<b>墙钟时间</b>（{@code System.nanoTime}）而非注入的 Clock：
     * 注入时钟用于控制存储侧租约过期语义（可虚拟推进），
     * 阻塞等待必须以真实流逝时间为准，否则虚拟时钟下会无限等待。
     * </p>
     *
     * @param timeoutMillis 最长等待时间（毫秒）
     * @return 锁句柄
     * @throws KvLockTimeoutException 超时未获取
     */
    public KvLock acquire(String name, long leaseMillis, long timeoutMillis)
            throws KvLockTimeoutException {
        long deadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (true) {
            KvLock lock = tryAcquire(name, leaseMillis);
            if (lock != null) {
                return lock;
            }
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(
                    deadlineNanos - System.nanoTime());
            if (remainingMillis <= 0) {
                throw new KvLockTimeoutException(
                        "Acquire lock timeout|lock=" + name + "|timeoutMs=" + timeoutMillis);
            }
            sleep(Math.min(config.getRetryIntervalMillis(), remainingMillis));
        }
    }

    /**
     * 续约指定持有的锁（校验令牌，绝不续期他人的锁）
     *
     * @return {@code true} 续约成功；{@code false} 表示锁已丢失（租约到期被接管），
     * 持有方应立即停止临界区工作
     */
    boolean renew(HeldLock held) {
        synchronized (held) {
            KvRecord current = store.get(lockKey(held.name));
            if (current == null || !current.getValue().equals(held.token)) {
                removeHeld(held);
                return false;
            }
            boolean renewed = casStore.compareAndSet(
                    lockKey(held.name),
                    held.token,
                    current.expire(held.leaseMillis, clock.millis()));
            if (!renewed) {
                removeHeld(held);
                log.warn("Lock renew lost (taken over by others?)|lock={}", held.name);
            }
            return renewed;
        }
    }

    /**
     * 释放锁（仅删除自己令牌持有的锁， fencing 安全）
     *
     * @return {@code true} 释放成功；{@code false} 表示锁已丢失（已被接管或过期），
     * 此时无需任何补偿——新持有者的锁不受影响
     */
    boolean release(HeldLock held) {
        removeHeld(held);
        return casStore.compareAndRemove(lockKey(held.name), held.token);
    }

    /**
     * 查询锁是否仍被自己持有（不触发续约）
     */
    boolean isHeld(HeldLock held) {
        KvRecord current = store.get(lockKey(held.name));
        return current != null && current.getValue().equals(held.token);
    }

    private void heartbeatLoop() {
        while (running) {
            long idleMillis = config.getHeartbeatIntervalMillis();
            synchronized (heartbeatSignal) {
                try {
                    heartbeatSignal.wait(heartbeatIntervalMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (!running) {
                return;
            }
            for (Set<HeldLock> locks : heldLocks.values()) {
                for (HeldLock held : locks) {
                    renewHeartbeat(held);
                }
            }
        }
    }

    /**
     * 心跳间隔自适应：取配置值与「最短持有租约的 1/3」中的较小者，
     * 保证短租约锁在过期前至少有两个续约窗口
     */
    private long heartbeatIntervalMillis() {
        long interval = config.getHeartbeatIntervalMillis();
        for (Set<HeldLock> locks : heldLocks.values()) {
            for (HeldLock held : locks) {
                interval = Math.min(interval, Math.max(1, held.leaseMillis / 3));
            }
        }
        return interval;
    }

    private SpaceKey lockKey(String name) {
        return SpaceKey.of(config.getSpace(), name);
    }

    private void checkRunning() {
        if (!running) {
            throw new IllegalStateException("KvLockManager already closed");
        }
    }

    /**
     * @return false 表示线程被中断，调用方应退出循环（避免 sleep 立即再抛导致忙转）
     */
    private boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void close() {
        running = false;
        synchronized (heartbeatSignal) {
            heartbeatSignal.notifyAll();
        }
        for (Set<HeldLock> locks : new HashMap<>(heldLocks).values()) {
            for (HeldLock held : new HashSet<>(locks)) {
                try {
                    release(held);
                } catch (RuntimeException e) {
                    log.warn("Release on close failed|lock={}", held.name, e);
                }
            }
        }
    }

    private void renewHeartbeat(HeldLock held) {
        try {
            renew(held);
        } catch (RuntimeException e) {
            log.warn("Lock heartbeat failed|lock={}", held.name, e);
        }
    }

    private void removeHeld(HeldLock held) {
        heldLocks.computeIfPresent(held.name, (ignored, locks) -> {
            locks.remove(held);
            return locks.isEmpty() ? null : locks;
        });
    }

    /**
     * 持有状态（锁名 + 不可伪造的持有者令牌）
     */
    static final class HeldLock {

        final String name;
        final String token;
        final long leaseMillis;

        HeldLock(String name, String token, long leaseMillis) {
            this.name = name;
            this.token = token;
            this.leaseMillis = leaseMillis;
        }
    }

    /**
     * 锁配置
     *
     * @author jay.wu
     */
    @lombok.Data
    @lombok.experimental.Accessors(chain = true)
    public static class Config {

        /**
         * 默认锁键空间
         */
        public static final String DEFAULT_SPACE = "kv.lock";

        /**
         * 默认心跳间隔（毫秒）：对齐 lease/3 的业界惯例
         */
        public static final long DEFAULT_HEARTBEAT_INTERVAL_MILLIS = 10_000;

        /**
         * 默认获取重试间隔（毫秒）
         */
        public static final long DEFAULT_RETRY_INTERVAL_MILLIS = 200;

        /**
         * 锁键空间名
         */
        private String space = DEFAULT_SPACE;

        /**
         * 持有者标识，默认自动生成；多锁管理器共用存储时建议显式命名
         */
        private String ownerId;

        /**
         * 心跳续约间隔（毫秒）。必须显著小于 lease 时长，
         * 建议为 lease 的 1/3，容忍两次心跳失败仍不超时
         */
        private long heartbeatIntervalMillis = DEFAULT_HEARTBEAT_INTERVAL_MILLIS;

        /**
         * acquire 阻塞获取时的重试间隔（毫秒）
         */
        private long retryIntervalMillis = DEFAULT_RETRY_INTERVAL_MILLIS;

        void validate() {
            if (heartbeatIntervalMillis <= 0) {
                throw new IllegalArgumentException(
                        "heartbeatIntervalMillis must be positive");
            }
            if (retryIntervalMillis < 0) {
                throw new IllegalArgumentException(
                        "retryIntervalMillis must not be negative");
            }
        }
    }
}
