package com.team4u.framework.base.util;

import java.util.concurrent.TimeUnit;

/**
 * 线程工具类
 * <p>
 * 提供线程休眠等常用操作。
 * </p>
 *
 * @author jay.wu
 */
public class ThreadUtil {

    /**
     * 当前线程休眠指定毫秒数
     * <p>
     * 该方法封装了 {@link TimeUnit#sleep(long)}，自动捕获并处理 {@link InterruptedException}。
     * 若发生中断，会重新设置当前线程的中断状态。
     * </p>
     *
     * @param millis 休眠时长（毫秒）
     */
    public static void sleep(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
