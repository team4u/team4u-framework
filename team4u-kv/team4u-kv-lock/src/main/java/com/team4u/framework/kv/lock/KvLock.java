package com.team4u.framework.kv.lock;

/**
 * 锁句柄：一次成功持有的抽象，AutoCloseable 支持与 try-with-resources 组合
 *
 * <pre>{@code
 * try (KvLock lock = manager.acquire("report.daily", 30_000, 5_000)) {
 *     doGenerate();
 * }
 * }</pre>
 *
 * @author jay.wu
 */
public class KvLock implements AutoCloseable {

    private final KvLockManager manager;
    private final KvLockManager.HeldLock held;

    KvLock(KvLockManager manager, KvLockManager.HeldLock held) {
        this.manager = manager;
        this.held = held;
    }

    /**
     * 锁名
     */
    public String name() {
        return held.name;
    }

    /**
     * 本次持有者的只读令牌。
     * <p>
     * 令牌同时也是锁记录的 CAS 匹配值。调用方可以把它复制到自己的 fencing
     * envelope，但不能通过本方法改变锁状态。
     * </p>
     */
    public String token() {
        return held.token;
    }

    /**
     * 主动续约（心跳之外的手动触发）
     *
     * @return {@code false} 表示锁已丢失，应立即停止临界区工作
     */
    public boolean renew() {
        return manager.renew(held);
    }

    /**
     * 查询是否仍被自己持有
     */
    public boolean isHeld() {
        return manager.isHeld(held);
    }

    /**
     * 释放锁（fencing 安全，见 {@link KvLockManager}）
     */
    public boolean release() {
        return manager.release(held);
    }

    @Override
    public void close() {
        release();
    }
}
