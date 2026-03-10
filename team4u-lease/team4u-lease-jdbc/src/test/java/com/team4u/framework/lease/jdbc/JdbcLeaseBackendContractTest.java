package com.team4u.framework.lease.jdbc;

import com.team4u.framework.lease.AbstractLeaseContractSupport;
import com.team4u.framework.lease.enums.LeaseAdminResult;
import com.team4u.framework.lease.enums.LeaseTaskState;
import com.team4u.framework.lease.model.LeasePublishRequest;
import com.team4u.framework.lease.model.LeaseQueryRequest;
import org.junit.Assert;
import org.junit.Test;

public class JdbcLeaseBackendContractTest extends AbstractLeaseContractSupport {

    @Override
    protected com.team4u.framework.lease.api.LeaseBackend createBackend() {
        return new JdbcLeaseBackend(JdbcLeaseBackendTestSupport.newDataSource());
    }

    @Test
    public void testQueryFiltersStillWorkOnDedicatedSchema() {
        JdbcLeaseBackend backend = new JdbcLeaseBackend(JdbcLeaseBackendTestSupport.newDataSource());
        backend.publish(LeasePublishRequest.builder().queue("queue-a").taskType("pay").payload("a").priority(5).build());
        backend.publish(LeasePublishRequest.builder().queue("queue-b").taskType("mail").payload("b").build());

        Assert.assertEquals(1, backend.list(LeaseQueryRequest.builder()
                .queue("queue-a")
                .taskType("pay")
                .state(LeaseTaskState.READY)
                .build()).getItems().size());
    }

    @Test
    public void testRescheduleWorksAgainstDedicatedSchema() {
        JdbcLeaseBackend backend = new JdbcLeaseBackend(JdbcLeaseBackendTestSupport.newDataSource());
        String taskId = publish(backend, "pay", "payload", 200L);

        Assert.assertEquals(LeaseAdminResult.APPLIED, backend.reschedule(taskId, 0L));
        Assert.assertTrue(backend.get(taskId).isPresent());
        Assert.assertEquals(LeaseTaskState.READY, backend.get(taskId).get().getState());
    }
}
