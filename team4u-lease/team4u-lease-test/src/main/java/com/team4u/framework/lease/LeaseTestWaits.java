package com.team4u.framework.lease;

import org.junit.Assert;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * 共享的紧轮询等待辅助：用「期限 + 10ms 轮询」取代固定 sleep，
 * 条件满足即返回，超时才失败，避免测试为等待异步结果而空转固定时长。
 */
public final class LeaseTestWaits {

    /** 默认等待期限：足够覆盖慢 CI 上的调度抖动，仅为上限而非固定耗时。 */
    public static final long DEFAULT_TIMEOUT_MILLIS = 2_000L;

    /** 轮询间隔：10ms 紧轮询，条件满足后最多延迟一个间隔即返回。 */
    public static final long POLL_INTERVAL_MILLIS = 10L;

    private LeaseTestWaits() {
    }

    /**
     * 等待条件成立，成立立即返回；超过 {@code timeoutMillis} 仍未成立则失败。
     */
    public static void awaitTrue(BooleanSupplier condition, long timeoutMillis, String message)
            throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadlineNanos) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        Assert.fail(message + " (not met within " + timeoutMillis + "ms)");
    }

    public static void awaitTrue(BooleanSupplier condition, String message)
            throws InterruptedException {
        awaitTrue(condition, DEFAULT_TIMEOUT_MILLIS, message);
    }

    /**
     * 等待当前时间越过 {@code boundary + marginMillis}，越界即刻返回。
     */
    public static void awaitAfter(Instant boundary, long marginMillis)
            throws InterruptedException {
        final Instant required = boundary.plusMillis(marginMillis);
        awaitTrue(new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return Instant.now().isAfter(required);
            }
        }, "time did not pass boundary " + boundary);
    }
}
