package com.team4u.framework.lease;

import com.team4u.framework.lease.runtime.LeaseWorkerPolicy;
import org.junit.Assert;
import org.junit.Test;

public class LeaseWorkerPolicyTest {

    @Test
    public void testHeartbeatIntervalDefaultsToLeaseFraction() {
        LeaseWorkerPolicy policy = LeaseWorkerPolicy.builder()
                .leaseMillis(9_000L)
                .build();

        Assert.assertEquals(3_000L, policy.getHeartbeatIntervalMillis());
    }

    @Test
    public void testHeartbeatIntervalMustBeLessThanLease() {
        try {
            LeaseWorkerPolicy.builder()
                    .leaseMillis(5_000L)
                    .heartbeatEnabled(true)
                    .heartbeatIntervalMillis(5_000L)
                    .build();
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            Assert.assertTrue(ex.getMessage().contains("heartbeatIntervalMillis"));
        }
    }

    @Test
    public void testMissingHandlerRetryDelayDefaultsToPollWait() {
        LeaseWorkerPolicy policy = LeaseWorkerPolicy.builder()
                .pollWaitMillis(250L)
                .build();

        Assert.assertEquals(250L, policy.getMissingHandlerRetryDelayMillis());
    }

    @Test
    public void testMissingHandlerRetryDelayRejectsNegativeValue() {
        try {
            LeaseWorkerPolicy.builder()
                    .missingHandlerRetryDelayMillis(-1L)
                    .build();
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            Assert.assertTrue(ex.getMessage().contains("missingHandlerRetryDelayMillis"));
        }
    }
}
