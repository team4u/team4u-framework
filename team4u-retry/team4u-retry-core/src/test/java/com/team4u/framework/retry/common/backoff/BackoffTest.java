package com.team4u.framework.retry.common.backoff;

import com.team4u.framework.retry.config.BackoffConfig;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class BackoffTest {

    @Test
    public void testRejectsInvalidArguments() {
        assertIllegalArgument(() -> Backoffs.fixed(-1L));
        assertIllegalArgument(() -> Backoffs.increment(1L, -1L));
        assertIllegalArgument(() -> Backoffs.exponential(1L, 0D, 10L));
    }

    @Test
    public void testRejectsNonPositiveAttempt() {
        try {
            Backoffs.fixed(10L).calculateMillis(0);
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            Assert.assertTrue(ex.getMessage().contains("attempt"));
        }
    }

    @Test
    public void testGenericBuilderUsesExactExponentialJitterType() {
        Backoff builderBackoff = Backoffs.builder("exponentialJitter")
                .param("initialDelay", 100L)
                .param("multiplier", 2.0D)
                .param("maxDelay", 100L)
                .build();

        BackoffConfig config = new BackoffConfig();
        config.setType("exponentialJitter");
        config.setParams(Collections.singletonMap("initialDelay", 100L));
        Backoff configBackoff = BackoffRegistry.global().createBackoff(config);

        Assert.assertTrue(builderBackoff.calculateMillis(1) >= 100L);
        Assert.assertTrue(builderBackoff.calculateMillis(1) <= 100L);
        Assert.assertTrue(configBackoff.calculateMillis(1) >= 100L);
    }

    @Test
    public void testGenericBuilderReusesRegistryCacheForEquivalentConfig() {
        Backoff builderBackoff = Backoffs.builder("fixed")
                .param("delay", 88L)
                .build();

        BackoffConfig config = new BackoffConfig();
        config.setType("fixed");
        config.setParams(Collections.singletonMap("delay", 88L));

        Backoff configBackoff = BackoffRegistry.global().createBackoff(config);

        Assert.assertSame(builderBackoff, configBackoff);
    }

    @Test
    public void testGenericBuilderRejectsCaseMismatchedType() {
        assertIllegalArgument(new Runnable() {
            @Override
            public void run() {
                Backoffs.builder("ExponentialJitter")
                        .param("initialDelay", 100L)
                        .build();
            }
        });
    }

    @Test
    public void testCreateBackoffFallsBackToFixedForBlankType() {
        BackoffConfig config = new BackoffConfig();
        config.setType("  ");
        config.setParams(Collections.singletonMap("delay", 123L));

        Backoff backoff = BackoffRegistry.global().createBackoff(config);

        Assert.assertEquals(123L, backoff.calculateMillis(1));
    }

    @Test
    public void testGenericBuilderFallsBackToFixedForBlankType() {
        Backoff backoff = Backoffs.builder("  ")
                .param("delay", 321L)
                .build();

        Assert.assertEquals(321L, backoff.calculateMillis(1));
    }

    @Test
    public void testCreateBackoffRejectsUnknownType() {
        assertIllegalArgument(new Runnable() {
            @Override
            public void run() {
                BackoffConfig config = new BackoffConfig();
                config.setType("unknown");
                BackoffRegistry.global().createBackoff(config);
            }
        });
    }

    @Test
    public void testEquivalentConfigsReuseCachedBackoffInstance() {
        BackoffRegistry registry = new BackoffRegistry();

        BackoffConfig config1 = new BackoffConfig();
        config1.setType("fixed");
        config1.setParams(Collections.singletonMap("delay", 100L));
        BackoffConfig config2 = new BackoffConfig();
        config2.setType("fixed");
        config2.setParams(Collections.singletonMap("delay", 100L));

        Backoff backoff1 = registry.createBackoff(config1);
        Backoff backoff2 = registry.createBackoff(config2);

        Assert.assertSame(backoff1, backoff2);
    }

    @Test
    public void testMutatingOriginalConfigDoesNotPoisonExistingCacheKey() {
        BackoffRegistry registry = new BackoffRegistry();
        BackoffConfig original = new BackoffConfig();
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("delay", 100L);
        original.setType("fixed");
        original.setParams(params);

        Backoff backoff1 = registry.createBackoff(original);

        params.put("delay", 200L);

        BackoffConfig sameAsOriginal = new BackoffConfig();
        sameAsOriginal.setType("fixed");
        sameAsOriginal.setParams(Collections.singletonMap("delay", 100L));

        Backoff backoff2 = registry.createBackoff(sameAsOriginal);

        Assert.assertSame(backoff1, backoff2);
        Assert.assertEquals(100L, backoff2.calculateMillis(1));
    }

    @Test
    public void testExponentialBackoffSaturatesAtMaxDelayForHugeAttempt() {
        Backoff backoff = Backoffs.exponential(100L, 10D, 500L);

        Assert.assertEquals(500L, backoff.calculateMillis(1000));
    }

    private void assertIllegalArgument(Runnable runnable) {
        try {
            runnable.run();
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            Assert.assertNotNull(expected.getMessage());
        }
    }


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
