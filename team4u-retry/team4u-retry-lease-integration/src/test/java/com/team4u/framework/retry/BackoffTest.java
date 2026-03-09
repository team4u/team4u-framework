package com.team4u.framework.retry;

import org.junit.Assert;
import org.junit.Test;

public class BackoffTest {

    @Test
    public void testFixed() {
        // 测试固定延迟策略
        Backoff fixedBackoff = Backoffs.fixed(1000);

        Assert.assertEquals(1000, fixedBackoff.calculateMillis(1));
        Assert.assertEquals(1000, fixedBackoff.calculateMillis(2));
        Assert.assertEquals(1000, fixedBackoff.calculateMillis(10));
    }

    @Test
    public void testIncrement() {
        // 测试增量延迟策略：初始1000ms，每次增加500ms
        Backoff incrementBackoff = Backoffs.increment(1000, 500);

        Assert.assertEquals(1000, incrementBackoff.calculateMillis(1));
        Assert.assertEquals(1500, incrementBackoff.calculateMillis(2));
        Assert.assertEquals(2000, incrementBackoff.calculateMillis(3));
    }

    @Test
    public void testExponential() {
        // 测试指数延迟策略：初始100ms，每次翻倍，最大不超过500ms
        Backoff exponentialBackoff = Backoffs.exponential(100, 2.0, 500);

        Assert.assertEquals(100, exponentialBackoff.calculateMillis(1));
        Assert.assertEquals(200, exponentialBackoff.calculateMillis(2));
        Assert.assertEquals(400, exponentialBackoff.calculateMillis(3));
        Assert.assertEquals(500, exponentialBackoff.calculateMillis(4));
        Assert.assertEquals(500, exponentialBackoff.calculateMillis(10));
    }

    @Test
    public void testExponentialJitter() {
        // 测试带抖动的指数延迟策略：初始100ms，每次翻倍，最大不超过1000ms
        Backoff jitterBackoff = Backoffs.exponentialJitter(100, 2.0, 1000);

        for (int attempt = 1; attempt <= 5; attempt++) {
            long delay = jitterBackoff.calculateMillis(attempt);
            long maxExpected = Math.min((long) (100 * Math.pow(2.0, attempt - 1)), 1000);

            // 确保产生的延迟时间在基础值和计算出的最大值之间
            Assert.assertTrue("Delay should be >= 100", delay >= 100);
            Assert.assertTrue("Delay should be <= " + (maxExpected + 1), delay <= maxExpected + 1);
        }
    }

    @Test
    public void testExponentialJitterBoundary() {
        try {
            Backoffs.exponentialJitter(1000, 2.0, 500);
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            Assert.assertTrue(ex.getMessage().contains("maxDelay"));
        }
    }
}
