package com.team4u.framework.base.refresh;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 可手动推进的虚拟时钟（UTC 时区），供 RefreshableValue 测试使用。
 * <p>
 * 默认为手动模式：通过 {@link #advanceMillis(long)} / {@link #setInstant(Instant)} 推进虚拟时间；
 * 后台任务等依赖真实周期的场景可调用 {@link #enableRealTimeSync()}，
 * 使虚拟时钟随真实时间同步推进（基于 nanoTime 差值，避免真实 sleep 干扰断言精度）。
 *
 * @author jay.wu
 */
class MutableClock extends Clock {

    private volatile Instant current = Instant.ofEpochMilli(1_000_000);
    /**
     * 真实时间同步基准（nanoTime），-1 表示未开启
     */
    private volatile long realTimeSyncBaseNanos = -1L;

    /**
     * 直接设置当前虚拟时间（同时关闭真实时间同步）
     *
     * @param instant 目标时间
     */
    void setInstant(Instant instant) {
        this.realTimeSyncBaseNanos = -1L;
        this.current = instant;
    }

    /**
     * 手动推进虚拟时间
     *
     * @param millis 推进毫秒数
     */
    void advanceMillis(long millis) {
        this.current = current.plusMillis(millis);
    }

    /**
     * 开启真实时间同步：虚拟时钟随真实流逝时间推进（以当前虚拟时间为起点）
     */
    void enableRealTimeSync() {
        this.realTimeSyncBaseNanos = System.nanoTime();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        long base = realTimeSyncBaseNanos;
        if (base < 0) {
            return current;
        }
        return current.plusMillis((System.nanoTime() - base) / 1_000_000);
    }
}
