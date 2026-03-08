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
}
