package com.team4u.framework.lease.jdbc;

import com.team4u.framework.lease.api.LeaseBackend;
import com.team4u.framework.lease.model.LeaseCloseRequest;
import com.team4u.framework.lease.model.LeaseGrant;
import com.team4u.framework.lease.model.LeaseTaskRecord;
import com.team4u.framework.lease.model.LeaseUpdateRequest;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;

public class JdbcLeasePartialUpdateTest {

    private final LeaseBackend backend = new JdbcLeaseBackend(JdbcLeaseBackendTestSupport.newDataSource());

    @Test
    public void testRuntimeCloseWithNullPayloadKeepsOriginal() throws Exception {
        String taskId = backend.publish(com.team4u.framework.lease.model.LeasePublishRequest.builder()
                .queue("q1")
                .taskType("t1")
                .payload("original-payload")
                .build());

        LeaseGrant grant = backend.acquire(com.team4u.framework.lease.model.LeaseAcquireRequest.builder()
                .workerId("w1")
                .leaseMillis(1000L)
                .waitTimeoutMillis(1000L)
                .subscription(com.team4u.framework.lease.model.LeaseSubscription.builder().queue("q1").build())
                .build());

        Assert.assertNotNull(grant);

        // Close with null payload
        backend.close(grant.getHandle(), LeaseCloseRequest.builder()
                .outcome(com.team4u.framework.lease.enums.LeaseTaskOutcome.SUCCEEDED)
                .payload(null)
                .build());

        LeaseTaskRecord record = backend.get(taskId).get();
        Assert.assertEquals("original-payload", record.getPayload());
    }

    @Test
    public void testAdminCloseWithNullPayloadKeepsOriginal() throws Exception {
        String taskId = backend.publish(com.team4u.framework.lease.model.LeasePublishRequest.builder()
                .queue("q1")
                .taskType("t1")
                .payload("original-payload")
                .build());

        // Admin close with null payload
        backend.close(taskId, LeaseCloseRequest.builder()
                .outcome(com.team4u.framework.lease.enums.LeaseTaskOutcome.CANCELLED)
                .payload(null)
                .build());

        LeaseTaskRecord record = backend.get(taskId).get();
        Assert.assertEquals("original-payload", record.getPayload());
    }

    @Test
    public void testUpdateWithNullFieldsKeepsOriginal() throws Exception {
        String taskId = backend.publish(com.team4u.framework.lease.model.LeasePublishRequest.builder()
                .queue("q1")
                .taskType("t1")
                .payload("original-payload")
                .attributes(Collections.singletonMap("k1", "v1"))
                .build());

        // Update with null fields
        backend.update(LeaseUpdateRequest.builder()
                .taskId(taskId)
                .payload(null)
                .attributes(null)
                .build());

        LeaseTaskRecord record = backend.get(taskId).get();
        Assert.assertEquals("original-payload", record.getPayload());
        Assert.assertEquals("v1", record.getAttributes().get("k1"));
    }
}
