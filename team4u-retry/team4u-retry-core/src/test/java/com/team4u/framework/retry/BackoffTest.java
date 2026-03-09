package com.team4u.framework.retry;

import com.team4u.framework.retry.backoff.Backoff;
import com.team4u.framework.retry.backoff.BackoffRegistry;
import com.team4u.framework.retry.backoff.Backoffs;
import com.team4u.framework.retry.config.BackoffConfig;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;

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
    public void testGenericBuilderNormalizesExponentialJitterType() {
        Backoff builderBackoff = Backoffs.builder("ExponentialJitter")
                .param("initialDelay", 100L)
                .param("multiplier", 2.0D)
                .param("maxDelay", 500L)
                .build();

        BackoffConfig config = new BackoffConfig();
        config.setType("exponentialjitter");
        config.setParams(Collections.singletonMap("initialDelay", 100L));
        Backoff configBackoff = BackoffRegistry.global().createBackoff(config);

        BackoffConfig camelCaseConfig = new BackoffConfig();
        camelCaseConfig.setType("exponentialJitter");
        camelCaseConfig.setParams(Collections.singletonMap("initialDelay", 100L));
        Backoff camelCaseBackoff = BackoffRegistry.global().createBackoff(camelCaseConfig);

        Assert.assertTrue(builderBackoff.calculateMillis(1) >= 100L);
        Assert.assertTrue(builderBackoff.calculateMillis(1) <= 100L);
        Assert.assertTrue(configBackoff.calculateMillis(1) >= 100L);
        Assert.assertTrue(camelCaseBackoff.calculateMillis(1) >= 100L);
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
