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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 固定窗口算法单元测试：窗口内阈值、跨窗归零、多许可
 *
 * @author jay.wu
 */
public class FixedWindowAlgorithmTest {

    private final TestKvContext kv = TestKvContext.create();
    private final InMemoryKvStore store = kv.store();
    private final FixedWindowAlgorithm algorithm = new FixedWindowAlgorithm();

    @After
    public void tearDown() {
        kv.close();
    }

    private static RateLimitRule rule(long windowMillis, long threshold) {
        RateLimitRule rule = new RateLimitRule();
        rule.setId("fw");
        rule.setAlgorithm(FixedWindowAlgorithm.KEY);
        rule.setWindowMillis(windowMillis);
        rule.setThreshold(threshold);
        return rule;
    }

    private RateLimitResult acquire(RateLimitRule rule, String key, int permits) {
        return algorithm.tryAcquire(rule, store, key, null, kv.clock().millis(), permits);
    }

    @Test
    public void thresholdReachedWithinWindow() {
        RateLimitRule rule = rule(1000, 2);

        RateLimitResult first = acquire(rule, "fw.u1", 1);
        assertTrue(first.isAllowed());
        assertEquals(Long.valueOf(1), first.getRemaining());
        assertEquals(RateLimitReason.PASS, first.getReason());

        RateLimitResult second = acquire(rule, "fw.u1", 1);
        assertTrue(second.isAllowed());
        assertEquals(Long.valueOf(0), second.getRemaining());

        RateLimitResult third = acquire(rule, "fw.u1", 1);
        assertFalse(third.isAllowed());
        assertEquals(RateLimitReason.THRESHOLD, third.getReason());
        assertEquals("fw", third.getRuleId());
        assertEquals(Long.valueOf(0), third.getRemaining());
        assertNull("浮窗无法精确给出重试等待", third.getRetryAfterMillis());
    }

    @Test
    public void counterResetsAfterWindowExpiry() {
        RateLimitRule rule = rule(1000, 2);
        assertTrue(acquire(rule, "fw.u2", 1).isAllowed());
        assertTrue(acquire(rule, "fw.u2", 1).isAllowed());
        assertFalse(acquire(rule, "fw.u2", 1).isAllowed());

        // 推进过窗：TTL 自首次递增起算，过期后从 0 重新计数
        kv.advanceMillis(1001);
        RateLimitResult after = acquire(rule, "fw.u2", 1);
        assertTrue(after.isAllowed());
        assertEquals(Long.valueOf(1), after.getRemaining());
    }

    @Test
    public void multiplePermitsConsumedAtomically() {
        RateLimitRule rule = rule(1000, 5);

        RateLimitResult bulk = acquire(rule, "fw.u3", 3);
        assertTrue(bulk.isAllowed());
        assertEquals(Long.valueOf(2), bulk.getRemaining());

        // 再取 3 个：计数 3+3=6 超过阈值 5，拒绝且 remaining 归零展示
        RateLimitResult over = acquire(rule, "fw.u3", 3);
        assertFalse(over.isAllowed());
        assertEquals(Long.valueOf(0), over.getRemaining());
    }

    @Test
    public void differentKeysCountIndependently() {
        RateLimitRule rule = rule(1000, 1);
        assertTrue(acquire(rule, "fw.a", 1).isAllowed());
        assertTrue(acquire(rule, "fw.b", 1).isAllowed());
        assertFalse(acquire(rule, "fw.a", 1).isAllowed());
    }
}
