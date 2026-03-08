package com.team4u.framework.retry;

import org.junit.Assert;
import org.junit.Test;

public class BackoffTest {

    @Test
    public void testRejectsInvalidArguments() {
        assertIllegalArgument(new Runnable() {
            @Override
            public void run() {
                Backoff.fixed(-1L);
            }
        });
        assertIllegalArgument(new Runnable() {
            @Override
            public void run() {
                Backoff.increment(1L, -1L);
            }
        });
        assertIllegalArgument(new Runnable() {
            @Override
            public void run() {
                Backoff.exponential(1L, 0D, 10L);
            }
        });
    }

    @Test
    public void testRejectsNonPositiveAttempt() {
        try {
            Backoff.fixed(10L).calculateMillis(0);
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            Assert.assertTrue(ex.getMessage().contains("attempt"));
        }
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
