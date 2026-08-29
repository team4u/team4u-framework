package com.team4u.framework.base.util;

import org.junit.Assert;
import org.junit.Test;

/**
 * ThreadUtil 新增能力单元测试（不可中断休眠、安静休眠）
 *
 * @author jay.wu
 */
public class ThreadUtilSleepVariantTest {

    @Test
    public void sleepUninterruptiblyCompletesFullDuration() throws Exception {
        long sleepMillis = 200L;
        long[] elapsedHolder = new long[1];
        Thread thread = new Thread(() -> {
            long start = System.currentTimeMillis();
            ThreadUtil.sleepUninterruptibly(sleepMillis);
            elapsedHolder[0] = System.currentTimeMillis() - start;
        });
        thread.start();
        // 休眠中途打断：休眠必须补足剩余时长
        Thread.sleep(50L);
        thread.interrupt();
        thread.join(2_000L);
        Assert.assertFalse("线程应在超时前结束", thread.isAlive());
        Assert.assertTrue("不可中断休眠应补足完整时长，实际 " + elapsedHolder[0] + "ms",
                elapsedHolder[0] >= sleepMillis - 15L);
    }

    @Test
    public void sleepUninterruptiblyRestoresInterruptFlag() throws Exception {
        Thread thread = new Thread(() -> {
            Thread.currentThread().interrupt();
            // 在已中断状态下执行：立即「中断」但不中断休眠，需正常返回且恢复标志
            ThreadUtil.sleepUninterruptibly(10L);
            Assert.assertTrue("中断标志应被保留", Thread.currentThread().isInterrupted());
        });
        thread.start();
        thread.join(2_000L);
        Assert.assertFalse("线程应在超时前结束", thread.isAlive());
    }

    @Test
    public void sleepUninterruptiblyWithNonPositiveMillisReturnsImmediately() {
        long start = System.currentTimeMillis();
        ThreadUtil.sleepUninterruptibly(0L);
        ThreadUtil.sleepUninterruptibly(-5L);
        Assert.assertTrue("非正数时长应立即返回", System.currentTimeMillis() - start < 100L);
    }

    @Test
    public void sleepQuietlyReturnsTrueWhenUninterrupted() {
        Assert.assertTrue("完整休眠应返回 true", ThreadUtil.sleepQuietly(10L));
        Assert.assertTrue("非正数时长应返回 true", ThreadUtil.sleepQuietly(0L));
        Assert.assertTrue("负数时长应返回 true", ThreadUtil.sleepQuietly(-1L));
    }

    @Test
    public void sleepQuietlyReturnsFalseAndRestoresFlagOnInterrupt() throws Exception {
        long[] elapsedHolder = new long[1];
        boolean[] resultHolder = new boolean[1];
        boolean[] flagHolder = new boolean[1];
        Thread thread = new Thread(() -> {
            long start = System.currentTimeMillis();
            resultHolder[0] = ThreadUtil.sleepQuietly(5_000L);
            elapsedHolder[0] = System.currentTimeMillis() - start;
            flagHolder[0] = Thread.currentThread().isInterrupted();
        });
        thread.start();
        Thread.sleep(50L);
        thread.interrupt();
        thread.join(2_000L);
        Assert.assertFalse("线程应在超时前结束", thread.isAlive());
        Assert.assertFalse("被中断的休眠应返回 false", resultHolder[0]);
        Assert.assertTrue("中断后应立即返回而非睡满", elapsedHolder[0] < 2_000L);
        Assert.assertTrue("中断标志应被恢复", flagHolder[0]);
    }
}
