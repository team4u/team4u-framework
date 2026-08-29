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
 * 令牌桶算法单元测试：满桶突发、速率回复、拒绝重试等待、键卫生
 *
 * @author jay.wu
 */
public class TokenBucketAlgorithmTest {

    private final TestKvContext kv = TestKvContext.create();
    private final InMemoryKvStore store = kv.store();
    private final TokenBucketAlgorithm algorithm = new TokenBucketAlgorithm();

    @After
    public void tearDown() {
        kv.close();
    }

    /**
     * 容量 5、5000ms 注满 → 速率 0.001 令牌/毫秒（1 个/秒）
     */
    private static RateLimitRule bucket(long capacity, long refillMillis) {
        RateLimitRule rule = new RateLimitRule();
        rule.setId("tb");
        rule.setAlgorithm(TokenBucketAlgorithm.KEY);
        rule.setWindowMillis(refillMillis);
        rule.setThreshold(capacity);
        return rule;
    }

    private RateLimitResult acquire(RateLimitRule rule, String key, int permits) {
        return algorithm.tryAcquire(rule, null, store, key, null, kv.clock().millis(), permits);
    }

    @Test
    public void burstWithinCapacityAllPass() {
        RateLimitRule rule = bucket(5, 5000);
        for (int i = 4; i >= 0; i--) {
            RateLimitResult result = acquire(rule, "tb.u1", 1);
            assertTrue("第 " + (5 - i) + " 次应在容量内放行", result.isAllowed());
            assertEquals(Long.valueOf(i), result.getRemaining());
        }
    }

    @Test
    public void exceedCapacityDenied() {
        RateLimitRule rule = bucket(5, 5000);
        for (int i = 0; i < 5; i++) {
            assertTrue(acquire(rule, "tb.u2", 1).isAllowed());
        }
        RateLimitResult denied = acquire(rule, "tb.u2", 1);
        assertFalse(denied.isAllowed());
        assertEquals(RateLimitReason.THRESHOLD, denied.getReason());
        // 差 1 个令牌、速率 0.001/ms → 需等 1000ms
        assertEquals(Long.valueOf(1000L), denied.getRetryAfterMillis());
        assertEquals(Long.valueOf(0L), denied.getRemaining());
    }

    @Test
    public void tokensRefillByRateAsTimeAdvances() {
        RateLimitRule rule = bucket(5, 5000);
        for (int i = 0; i < 5; i++) {
            assertTrue(acquire(rule, "tb.u3", 1).isAllowed());
        }

        // 推进 1000ms：回复 1 个令牌
        kv.advanceMillis(1000);
        RateLimitResult refilled = acquire(rule, "tb.u3", 1);
        assertTrue(refilled.isAllowed());
        assertEquals(Long.valueOf(0L), refilled.getRemaining());

        // 再拒绝时仍差 1 个令牌
        RateLimitResult denied = acquire(rule, "tb.u3", 1);
        assertFalse(denied.isAllowed());
        assertEquals(Long.valueOf(1000L), denied.getRetryAfterMillis());

        // 推进 2500ms：回复 2.5 个令牌，取 1 个剩 1.5 → remaining=1
        kv.advanceMillis(2500);
        RateLimitResult partial = acquire(rule, "tb.u3", 1);
        assertTrue(partial.isAllowed());
        assertEquals(Long.valueOf(1L), partial.getRemaining());
    }

    @Test
    public void refillNeverExceedsCapacity() {
        RateLimitRule rule = bucket(2, 1000);
        assertTrue(acquire(rule, "tb.u4", 1).isAllowed());
        assertTrue(acquire(rule, "tb.u4", 1).isAllowed());

        // 长时间静默后也不会超过容量
        kv.advanceMillis(60_000);
        assertTrue(acquire(rule, "tb.u4", 1).isAllowed());
        assertTrue(acquire(rule, "tb.u4", 1).isAllowed());
        assertFalse(acquire(rule, "tb.u4", 1).isAllowed());
    }

    @Test
    public void idleBucketKeyExpiresAndRefillsFull() {
        RateLimitRule rule = bucket(5, 1000);
        assertTrue(acquire(rule, "tb.u5", 1).isAllowed());

        // 键卫生：记录过期时间 = now + 2*windowMillis，静默到期后整键消失、满桶重来
        kv.advanceMillis(2001);
        for (int i = 4; i >= 0; i--) {
            RateLimitResult result = acquire(rule, "tb.u5", 1);
            assertTrue(result.isAllowed());
            assertEquals(Long.valueOf(i), result.getRemaining());
        }
    }
}
