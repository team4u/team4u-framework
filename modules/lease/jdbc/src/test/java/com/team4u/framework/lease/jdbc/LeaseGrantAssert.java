package com.team4u.framework.lease.jdbc;

import com.team4u.framework.lease.api.TaskStatus;
import com.team4u.framework.lease.spi.LeaseGrant;
import org.junit.Assert;

final class LeaseGrantAssert {

    private LeaseGrantAssert() {
    }

    static void assertGrant(LeaseGrant grant, String taskId, String workerId) {
        Assert.assertNotNull(grant);
        Assert.assertEquals(taskId, grant.getHandle().getTaskId());
        Assert.assertEquals(workerId, grant.getHandle().getWorkerId());
        Assert.assertNotNull(grant.getHandle().getLeaseToken());
        Assert.assertEquals(TaskStatus.RUNNING, grant.getSnapshot().getStatus());
        Assert.assertEquals(workerId, grant.getSnapshot().getWorkerId());
    }
}
