package com.team4u.framework.base.util;

import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;

/**
 * DurationUtil 单元测试
 *
 * @author jay.wu
 */
public class DurationUtilTest {

    @Test
    public void requireExactMillis() {
        Assert.assertEquals("整秒换算失败", 30_000L,
                DurationUtil.requireExactMillis(Duration.ofSeconds(30), "lease"));
        Assert.assertEquals("毫秒级换算失败", 250L,
                DurationUtil.requireExactMillis(Duration.ofMillis(250), "pollInterval"));
        Assert.assertEquals("混合时长换算失败", 1_500L,
                DurationUtil.requireExactMillis(Duration.ofMillis(1_500), "x"));
    }

    @Test
    public void requireExactMillisWithDefaultName() {
        Assert.assertEquals("默认参数名换算失败", 1_000L,
                DurationUtil.requireExactMillis(Duration.ofSeconds(1)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void requireExactMillisRejectsNull() {
        DurationUtil.requireExactMillis(null, "lease");
    }

    @Test(expected = IllegalArgumentException.class)
    public void requireExactMillisRejectsNegative() {
        DurationUtil.requireExactMillis(Duration.ofMillis(-1), "lease");
    }

    @Test(expected = IllegalArgumentException.class)
    public void requireExactMillisRejectsSubMillis() {
        // 亚毫秒精度会被静默截断，必须拒绝
        DurationUtil.requireExactMillis(Duration.ofNanos(1_500_000), "lease");
    }

    @Test(expected = IllegalArgumentException.class)
    public void requireExactMillisRejectsOverflow() {
        // 超出 long 毫秒表示范围
        DurationUtil.requireExactMillis(
                Duration.ofSeconds(Long.MAX_VALUE / 1000L * 2L), "lease");
    }

    @Test
    public void requirePositiveMillis() {
        Assert.assertEquals("正时长换算失败", 100L,
                DurationUtil.requirePositiveMillis(Duration.ofMillis(100), "interval"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void requirePositiveMillisRejectsZero() {
        DurationUtil.requirePositiveMillis(Duration.ZERO, "interval");
    }

    @Test
    public void requireNonNegativeMillis() {
        Assert.assertEquals("零时长应合法", 0L,
                DurationUtil.requireNonNegativeMillis(Duration.ZERO, "timeout"));
        Assert.assertEquals("正时长换算失败", 50L,
                DurationUtil.requireNonNegativeMillis(Duration.ofMillis(50), "timeout"));
    }
}
