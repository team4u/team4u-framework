package com.team4u.framework.lease.spi;

import org.junit.Assert;
import org.junit.Test;

public class LeaseTimesTest {

    @Test
    public void testAddsEpochMillis() {
        Assert.assertEquals(10L, LeaseTimes.plusMillis(9L, 1L));
        Assert.assertEquals(Long.MAX_VALUE, LeaseTimes.plusMillis(Long.MAX_VALUE, 0L));
    }

    @Test
    public void testRejectsNegativeOperandsByName() {
        assertRejected(-1L, 1L, "now");
        assertRejected(1L, -1L, "duration");
    }

    @Test
    public void testRejectsOverflowWithoutWrapping() {
        try {
            LeaseTimes.plusMillis(Long.MAX_VALUE, 1L);
            Assert.fail("expected overflow to be rejected");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("now + duration"));
            Assert.assertTrue(expected.getMessage().contains("Long.MAX_VALUE"));
        }
    }

    private void assertRejected(long now, long duration, String name) {
        try {
            LeaseTimes.plusMillis(now, duration);
            Assert.fail("expected negative " + name + " to be rejected");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains(name));
        }
    }
}
