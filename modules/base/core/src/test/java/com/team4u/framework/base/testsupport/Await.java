package com.team4u.framework.base.testsupport;

import org.junit.Assert;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * 测试等待辅助：期限 + 短轮询，条件满足立即返回，超时按 message 断言失败。
 * <p>
 * 用于替代「sleep 固定时长等后台动作」的慢测试写法：正常路径在动作完成后立即返回，
 * 期限（1-2s）仅作兜底，不拖慢正常路径。
 * </p>
 *
 * @author jay.wu
 */
public final class Await {

    /**
     * 轮询间隔：足够细以快速捕捉条件成立，又不足以忙等烧 CPU
     */
    private static final long POLL_INTERVAL_MILLIS = 10L;

    private Await() {
    }

    /**
     * 等待条件成立；成立立即返回，超时以 message 断言失败
     *
     * @param timeoutMillis 期限（毫秒），仅作兜底
     * @param message       超时时的断言失败消息
     * @param condition     轮询条件
     */
    public static void awaitCondition(long timeoutMillis, String message, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleepQuietly(POLL_INTERVAL_MILLIS);
        }
        Assert.fail(message);
    }

    /**
     * 等待数值进入稳定（连续 windowMillis 内不变）并返回稳定值；超时以 message 断言失败。
     * <p>
     * 适用于「关闭后计数不再增长」类负向条件：windowMillis 应大于被观察动作的周期，
     * 这样若动作仍在发生，窗口内必然出现变化，不会误判稳定。
     * </p>
     *
     * @param windowMillis   稳定判定窗口（毫秒），应大于动作周期
     * @param timeoutMillis  总期限（毫秒）
     * @param message        超时时的断言失败消息
     * @param valueSupplier  被观察数值（如计数器）
     * @return 稳定后的数值
     */
    public static long awaitStable(long windowMillis, long timeoutMillis, String message,
                                   LongSupplier valueSupplier) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        long previous = valueSupplier.getAsLong();
        while (System.currentTimeMillis() < deadline) {
            sleepQuietly(windowMillis);
            long current = valueSupplier.getAsLong();
            if (current == previous) {
                return current;
            }
            previous = current;
        }
        Assert.fail(message);
        return -1L;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Assert.fail("等待被中断");
        }
    }
}
