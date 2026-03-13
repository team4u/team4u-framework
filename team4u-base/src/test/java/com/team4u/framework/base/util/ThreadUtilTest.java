package com.team4u.framework.base.util;

import org.junit.Assert;
import org.junit.Test;

/**
 * ThreadUtil 单元测试
 *
 * @author jay.wu
 */
public class ThreadUtilTest {

    @Test
    public void testSleep() {
        long start = System.currentTimeMillis();
        long millis = 10;
        // 执行休眠
        ThreadUtil.sleep(millis);
        long end = System.currentTimeMillis();
        // 允许少许误差
        Assert.assertTrue("休眠时长不足", (end - start) >= millis - 5);
    }

    @Test
    public void testSleepInterrupted() {
        Thread thread = new Thread(() -> {
            Thread.currentThread().interrupt();
            // 在已中断状态下执行休眠，应立即清除中断标志并退出休眠
            ThreadUtil.sleep(100);
            // ThreadUtil.sleep 内部重新设置了中断标志
            Assert.assertTrue("线程中断标志应被保留", Thread.currentThread().isInterrupted());
        });
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
