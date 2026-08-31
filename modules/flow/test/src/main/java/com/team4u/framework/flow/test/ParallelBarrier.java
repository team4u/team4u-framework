package com.team4u.framework.flow.test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 基于 CountDownLatch 的两分支（可扩展为 N 分支）并行屏障，用于验证 Local
 * 并行分支真并发（两个分支必须同时进入屏障才能释放，否则测试超时失败而非死锁）。
 *
 * <p>每个分支调用 {@link #enter()}：先 countDown 入场闭锁，再阻塞在释放闭锁上；
 * 测试线程以 {@link #awaitEntered(long)} 等待全部分支进入（带 timeout 保护），
 * 证实重叠后调用 {@link #release()} 放行。</p>
 */
public final class ParallelBarrier {

    private final int branches;
    private final CountDownLatch entered;
    private final CountDownLatch release = new CountDownLatch(1);

    public ParallelBarrier(int branches) {
        if (branches < 1) {
            throw new IllegalArgumentException("branches must be positive");
        }
        this.branches = branches;
        this.entered = new CountDownLatch(branches);
    }

    public int branches() {
        return branches;
    }

    /** 分支侧调用：登记入场后阻塞直到 release。阻塞被中断时提前返回。 */
    public void enter() throws InterruptedException {
        entered.countDown();
        release.await();
    }

    /** 带超时保护的分支侧 enter：超时返回 false（分支仍应尽快返回）。 */
    public boolean enter(long timeout, TimeUnit unit) throws InterruptedException {
        entered.countDown();
        return release.await(timeout, unit);
    }

    /** 测试线程等待全部分支进入屏障。timeout 毫秒内未齐返回 false（超时保护，不死等）。 */
    public boolean awaitEntered(long timeoutMillis) throws InterruptedException {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
        return entered.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    /** 放行全部分支（幂等）。 */
    public void release() {
        release.countDown();
    }
}
