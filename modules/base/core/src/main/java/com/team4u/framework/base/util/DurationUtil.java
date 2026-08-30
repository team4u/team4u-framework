package com.team4u.framework.base.util;

import java.time.Duration;

/**
 * {@link Duration} 毫秒换算工具类
 * <p>
 * 统一各模块对 {@link Duration} 的校验与毫秒换算逻辑：
 * 只接受「精确到毫秒」的时长——包含亚毫秒精度（纳秒余数）或超出 long 毫秒
 * 表示范围的时长都会被拒绝，避免静默截断导致的租约/TTL 语义漂移。
 * </p>
 * <p>
 * 本类由 team4u-lease 中两份重复的私有 Durations（api 包与 runtime 包）合并上移而来，
 * 语义与原实现完全一致（含异常消息），lease 侧可直接替换引用。
 * </p>
 *
 * @author jay.wu
 */
public final class DurationUtil {

    private DurationUtil() {
    }

    /**
     * 校验并将时长换算为精确的毫秒数
     * <p>
     * 要求：非 null、非负、无亚毫秒精度余数、可被 long 毫秒表示。
     * 任一要求不满足均抛出 {@link IllegalArgumentException}。
     * </p>
     *
     * @param duration 待换算时长
     * @param name     参数名，用于异常消息定位（如 "lease"、"timeout"）
     * @return 精确毫秒数
     * @throws IllegalArgumentException duration 为 null、为负或无法无损换算为毫秒
     */
    public static long requireExactMillis(Duration duration, String name) {
        if (duration == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        try {
            long seconds = duration.getSeconds();
            long nanos = duration.getNano();
            long millis = seconds * 1000L + nanos / 1_000_000L;
            if (nanos % 1_000_000L != 0L || millis / 1000L != seconds) {
                throw new ArithmeticException("lossy conversion");
            }
            return millis;
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(name + " must fit in milliseconds", ex);
        }
    }

    /**
     * 校验并将时长换算为精确的毫秒数（参数名默认为 "duration"）
     *
     * @param duration 待换算时长
     * @return 精确毫秒数
     * @throws IllegalArgumentException duration 为 null、为负或无法无损换算为毫秒
     */
    public static long requireExactMillis(Duration duration) {
        return requireExactMillis(duration, "duration");
    }

    /**
     * 校验并将时长换算为严格大于 0 的毫秒数
     * <p>
     * 适用于租约时长、心跳间隔等「零值无意义」的场景：除
     * {@link #requireExactMillis(Duration, String)} 的全部要求外，还要求结果大于 0。
     * </p>
     *
     * @param duration 待换算时长
     * @param name     参数名，用于异常消息定位
     * @return 严格大于 0 的毫秒数
     * @throws IllegalArgumentException duration 为 null、为负、为零或无法无损换算为毫秒
     */
    public static long requirePositiveMillis(Duration duration, String name) {
        long millis = requireExactMillis(duration, name);
        if (millis <= 0L) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return millis;
    }

    /**
     * 校验并将时长换算为非负毫秒数
     * <p>
     * 适用于超时等待、轮询间隔等「零值合法（表示不等待）」的场景。
     * </p>
     *
     * @param duration 待换算时长
     * @param name     参数名，用于异常消息定位
     * @return 大于等于 0 的毫秒数
     * @throws IllegalArgumentException duration 为 null、为负或无法无损换算为毫秒。
     *         注：此处的负值检查为防御性冗余（{@link #requireExactMillis}
     *         已先行拒绝负值），保留以增强可读性
     */
    public static long requireNonNegativeMillis(Duration duration, String name) {
        long millis = requireExactMillis(duration, name);
        if (millis < 0L) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return millis;
    }
}
