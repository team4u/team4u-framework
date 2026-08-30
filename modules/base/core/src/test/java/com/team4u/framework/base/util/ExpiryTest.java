package com.team4u.framework.base.util;

import org.junit.Assert;
import org.junit.Test;

/**
 * Expiry 单元测试
 *
 * @author jay.wu
 */
public class ExpiryTest {

    @Test
    public void expiryFrom() {
        Assert.assertEquals("普通加法失败", 1_100L, Expiry.expiryFrom(1_000L, 100L));
        Assert.assertEquals("零 ttl 应返回 now", 1_000L, Expiry.expiryFrom(1_000L, 0L));
    }

    @Test
    public void expiryFromSaturatesOnOverflow() {
        // now + ttl 上溢必须饱和为 Long.MAX_VALUE 而非环绕为负数
        Assert.assertEquals("上溢应饱和为 NEVER", Expiry.NEVER,
                Expiry.expiryFrom(Long.MAX_VALUE - 10L, 100L));
        Assert.assertEquals("NEVER + 任意 ttl 仍为 NEVER", Expiry.NEVER,
                Expiry.expiryFrom(Expiry.NEVER, Expiry.NEVER));
    }

    @Test
    public void expiryFromNowUsesWallClock() {
        long before = System.currentTimeMillis();
        long expiry = Expiry.expiryFromNow(5_000L);
        long after = System.currentTimeMillis();
        Assert.assertTrue("expiry 应落在 [before+5000, after+5000] 区间",
                expiry >= before + 5_000L && expiry <= after + 5_000L);
    }

    @Test
    public void remainingMillis() {
        Assert.assertEquals("剩余时间计算失败", 300L,
                Expiry.remainingMillis(1_000L, 700L));
        Assert.assertEquals("恰好到期应返回 0", 0L,
                Expiry.remainingMillis(1_000L, 1_000L));
        Assert.assertEquals("已过期应返回 0 而非负数", 0L,
                Expiry.remainingMillis(500L, 1_000L));
        Assert.assertEquals("NEVER 的剩余时间为 NEVER", Expiry.NEVER,
                Expiry.remainingMillis(Expiry.NEVER, 1_000L));
    }

    @Test
    public void isExpired() {
        Assert.assertTrue("到达过期时刻即视为过期", Expiry.isExpired(1_000L, 1_000L));
        Assert.assertTrue("超过过期时刻视为过期", Expiry.isExpired(999L, 1_000L));
        Assert.assertFalse("未过期", Expiry.isExpired(1_001L, 1_000L));
        Assert.assertFalse("NEVER 永不过期", Expiry.isExpired(Expiry.NEVER, Long.MAX_VALUE - 1L));
    }

    @Test
    public void saturatedAdd() {
        Assert.assertEquals("普通加法失败", 3L, Expiry.saturatedAdd(1L, 2L));
        Assert.assertEquals("上溢饱和", Long.MAX_VALUE,
                Expiry.saturatedAdd(Long.MAX_VALUE - 1L, 2L));
        Assert.assertEquals("NEVER 加任何值仍饱和", Long.MAX_VALUE,
                Expiry.saturatedAdd(Long.MAX_VALUE, Long.MAX_VALUE));
        Assert.assertEquals("加零不变", 42L, Expiry.saturatedAdd(42L, 0L));
    }
}
