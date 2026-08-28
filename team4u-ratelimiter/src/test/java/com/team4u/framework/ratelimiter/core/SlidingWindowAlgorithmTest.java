package com.team4u.framework.ratelimiter.core;

import com.team4u.framework.kv.memory.InMemoryKvStore;
import com.team4u.framework.kv.test.TestKvContext;
import com.team4u.framework.ratelimiter.api.RateLimitReason;
import com.team4u.framework.ratelimiter.api.RateLimitResult;
import com.team4u.framework.ratelimiter.config.RateLimitRule;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 滑动窗口算法单元测试：精确滚动、窗口边缘突发、窥探、阈值下调排干
 *
 * @author jay.wu
 */
public class SlidingWindowAlgorithmTest {

    private final TestKvContext kv = TestKvContext.create();
    private final InMemoryKvStore store = kv.store();
    private final SlidingWindowAlgorithm algorithm = new SlidingWindowAlgorithm();

    @After
    public void tearDown() {
        kv.close();
    }

    private static RateLimitRule rule(long windowMillis, long threshold) {
        RateLimitRule rule = new RateLimitRule();
        rule.setId("sw");
        rule.setAlgorithm(SlidingWindowAlgorithm.KEY);
        rule.setWindowMillis(windowMillis);
        rule.setThreshold(threshold);
        return rule;
    }

    private RateLimitResult acquire(RateLimitRule rule, String key, int permits) {
        return algorithm.tryAcquire(rule, store, key, null, kv.clock().millis(), permits);
    }

    @Test
    public void preciseRollingAtWindowEdge() {
        RateLimitRule rule = rule(1000, 2);
        assertTrue(acquire(rule, "sw.u1", 1).isAllowed());
        assertTrue(acquire(rule, "sw.u1", 1).isAllowed());

        // t=999：两成员（score=0）尚未滑出（cutoff=-1），第二段突发被拒
        kv.advanceMillis(999);
        RateLimitResult denied = acquire(rule, "sw.u1", 1);
        assertFalse(denied.isAllowed());
        assertEquals(RateLimitReason.THRESHOLD, denied.getReason());
        // 最老成员 score=0，滑出窗口需 0+1000-999=1ms
        assertEquals(Long.valueOf(1L), denied.getRetryAfterMillis());
        assertEquals(Long.valueOf(0L), denied.getRemaining());

        // t=1000：cutoff=0 恰好裁剪 score=0 的成员，精确滚动放行
        kv.advanceMillis(1);
        RateLimitResult rolled = acquire(rule, "sw.u1", 1);
        assertTrue(rolled.isAllowed());
        assertEquals(Long.valueOf(1L), rolled.getRemaining());
    }

    @Test
    public void peekDoesNotAddMembers() {
        RateLimitRule rule = rule(1000, 2);
        assertTrue(acquire(rule, "sw.u2", 1).isAllowed());

        // 窥探：不添加成员、不占额度
        RateLimitResult peek = acquire(rule, "sw.u2", 0);
        assertTrue(peek.isAllowed());
        assertEquals(Long.valueOf(1L), peek.getRemaining());
        RateLimitResult peekAgain = acquire(rule, "sw.u2", 0);
        assertTrue(peekAgain.isAllowed());
        assertEquals("窥探不得计数", Long.valueOf(1L), peekAgain.getRemaining());

        // 窥探后额度依旧：第二个许可仍可通过
        assertTrue(acquire(rule, "sw.u2", 1).isAllowed());
    }

    @Test
    public void retryAfterPreciseOnOldestMember() {
        RateLimitRule rule = rule(500, 1);
        assertTrue(acquire(rule, "sw.u3", 1).isAllowed());

        kv.advanceMillis(100);
        RateLimitResult denied = acquire(rule, "sw.u3", 1);
        assertFalse(denied.isAllowed());
        // 最老成员 score=0，滑出需 0+500-100=400ms
        assertEquals(Long.valueOf(400L), denied.getRetryAfterMillis());
    }

    @Test
    public void loweredThresholdDrainsAsWindowSlides() {
        // 阈值 2 时窗口内进入两个成员
        assertTrue(acquire(rule(1000, 2), "sw.u4", 1).isAllowed());
        assertTrue(acquire(rule(1000, 2), "sw.u4", 1).isAllowed());

        // 阈值下调为 1：存量成员仍在窗口内，拒绝
        RateLimitRule lowered = rule(1000, 1);
        kv.advanceMillis(600);
        assertFalse(acquire(lowered, "sw.u4", 1).isAllowed());
        assertEquals(Long.valueOf(0L), acquire(lowered, "sw.u4", 1).getRemaining());

        // 窗口滑过原成员后自然排干，按新阈值 1 放行一次
        kv.advanceMillis(400);
        RateLimitResult drained = acquire(lowered, "sw.u4", 1);
        assertTrue(drained.isAllowed());
        assertEquals(Long.valueOf(0L), drained.getRemaining());
        assertFalse(acquire(lowered, "sw.u4", 1).isAllowed());
    }

    @Test
    public void multiPermitsRejectedAtomically() {
        RateLimitRule rule = rule(1000, 3);
        assertTrue(acquire(rule, "sw.u5", 2).isAllowed());

        RateLimitResult third = acquire(rule, "sw.u5", 1);
        assertTrue(third.isAllowed());
        assertEquals(Long.valueOf(0L), third.getRemaining());

        // 2 个许可整体判断：3+2 > 3 拒绝且不添加任何成员
        assertFalse(acquire(rule, "sw.u5", 2).isAllowed());
        // 拒绝未占用额度，但 3+1 > 3 同样拒绝
        assertFalse(acquire(rule, "sw.u5", 1).isAllowed());
    }
}
