package com.team4u.framework.flow.test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 并行并发同步屏障测试工具（Parallel Execution Sync Barrier）。
 *
 * <p>基于 {@link CountDownLatch} 实现，用于测试多分支并行执行时（如 {@link com.team4u.framework.flow.Flow#parallel}）
 * 验证各分支是否真正实现并发交织运行，具备超时保护以防止测试死锁。</p>
 *
 * @author jay.wu
 */
public final class ParallelBarrier {

    @lombok.Getter
    @lombok.experimental.Accessors(fluent = true)
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
