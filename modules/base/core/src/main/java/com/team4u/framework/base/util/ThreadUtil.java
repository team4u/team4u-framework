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

    /**
     * 当前线程不可中断地休眠指定毫秒数
     * <p>
     * 休眠期间被中断时：<b>不清除中断状态会破坏调用方的取消语义，但绝对完整的休眠
     * 才是本方法的契约</b>——本方法会吞掉中断、恢复中断标志、并继续补足剩余休眠时间，
     * 保证返回时恰好休眠了指定的时长（毫秒精度）。中断事件不丢失：调用方后续的
     * sleep/join/阻塞调用会立即触发，也可通过 {@code Thread.currentThread().isInterrupted()}
     * 主动观测。
     * </p>
     * <p>
     * 适用场景：延迟重试的退避等待、必须完整执行的时间窗口对齐等「时长本身是正确性
     * 的一部分」的场合。若中断应优先于时长（如可取消的任务循环），请改用
     * {@link #sleepQuietly(long)}。
     * </p>
     *
     * @param millis 休眠时长（毫秒）；小于等于 0 时直接返回
     */
    public static void sleepUninterruptibly(long millis) {
        boolean interrupted = false;
        try {
            long endNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            long remainingNanos = endNanos - System.nanoTime();
            while (remainingNanos > 0L) {
                try {
                    TimeUnit.NANOSECONDS.sleep(remainingNanos);
                    return;
                } catch (InterruptedException e) {
                    // 吞掉中断但记录，休眠补足后统一恢复，避免丢失中断事件
                    interrupted = true;
                    remainingNanos = endNanos - System.nanoTime();
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 当前线程安静地休眠指定毫秒数，返回是否完整休眠
     * <p>
     * 中断策略：<b>中断优先于时长</b>——休眠期间被中断时立即返回 {@code false}，
     * 并恢复当前线程的中断状态（不清除标志），由调用方根据返回值决定退出循环还是继续；
     * 完整休眠（或时长小于等于 0 无需休眠）返回 {@code true}。
     * </p>
     * <p>
     * 适用场景：后台循环里的轮询间隔等待——被中断通常意味着关闭信号，
     * 调用方检查返回值后应尽快退出，避免「sleep 立即再抛导致忙转」或忽略关闭请求。
     * 此前 KvLockManager、PollingWatcher 等各自持有同款私有实现。
     * </p>
     *
     * @param millis 休眠时长（毫秒）；小于等于 0 时直接返回 {@code true}
     * @return {@code true} 表示完整休眠未被中断；{@code false} 表示休眠被中断
     * （中断状态已恢复，调用方应退出循环收尾）
     */
    public static boolean sleepQuietly(long millis) {
        if (millis <= 0L) {
            return true;
        }
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
