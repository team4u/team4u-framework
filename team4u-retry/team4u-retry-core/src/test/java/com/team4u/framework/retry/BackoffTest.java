package com.team4u.framework.retry;

import com.team4u.framework.retry.backoff.Backoff;
import com.team4u.framework.retry.backoff.BackoffRegistry;
import com.team4u.framework.retry.backoff.Backoffs;
import com.team4u.framework.retry.config.BackoffConfig;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class BackoffTest {

    @Test
    public void testRejectsInvalidArguments() {
        assertIllegalArgument(new Runnable() {
            @Override
            public void run() {
                Backoffs.fixed(-1L);
            }
        });
        assertIllegalArgument(new Runnable() {
            @Override
            public void run() {
                Backoffs.increment(1L, -1L);
            }
        });
        assertIllegalArgument(new Runnable() {
            @Override
            public void run() {
                Backoffs.exponential(1L, 0D, 10L);
            }
        });
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
                .param("maxDelay", 500L)
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
        config.setParams(Collections.<String, Object>singletonMap("delay", 123L));

        Backoff backoff = BackoffRegistry.global().createBackoff(config);

        Assert.assertEquals(123L, backoff.calculateMillis(1));
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
        config1.setParams(Collections.<String, Object>singletonMap("delay", 100L));
        BackoffConfig config2 = new BackoffConfig();
        config2.setType("fixed");
        config2.setParams(Collections.<String, Object>singletonMap("delay", 100L));

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
        sameAsOriginal.setParams(Collections.<String, Object>singletonMap("delay", 100L));

        Backoff backoff2 = registry.createBackoff(sameAsOriginal);

        Assert.assertSame(backoff1, backoff2);
        Assert.assertEquals(100L, backoff2.calculateMillis(1));
    }

    private void assertIllegalArgument(Runnable runnable) {
        try {
            runnable.run();
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            Assert.assertNotNull(expected.getMessage());
        }
    }
}
