package com.team4u.framework.lease.jdbc;

import com.team4u.framework.lease.model.LeasePublishRequest;
import com.team4u.framework.lease.model.LeasePublishResult;
import com.team4u.framework.lease.model.LeaseTaskRecord;
import org.junit.Assert;
import org.junit.Test;

import java.util.Optional;

public class JdbcLeaseBusinessKeyTest {

    @Test
    public void testPublishIfAbsentOnlyCreatesOneTaskPerQueueAndBusinessKey() {
        JdbcLeaseBackend backend = new JdbcLeaseBackend(JdbcLeaseBackendTestSupport.newDataSource());
        LeasePublishRequest request = LeasePublishRequest.builder()
                .taskGroup("retry-q")
                .taskType("recover-payment")
                .payload("{\"attempt\":1}")
                .businessKey("recover-payment|order-1001")
                .build();

        LeasePublishResult created = backend.publishIfAbsent(request);
        LeasePublishResult existing = backend.publishIfAbsent(LeasePublishRequest.builder()
                .taskGroup("retry-q")
                .taskType("recover-payment")
                .payload("{\"attempt\":2}")
                .businessKey("recover-payment|order-1001")
                .build());

        Assert.assertTrue(created.isCreated());
        Assert.assertFalse(existing.isCreated());
        Assert.assertEquals(created.getTaskId(), existing.getTaskId());
        Assert.assertNotNull(existing.getRecord());
        Assert.assertEquals("{\"attempt\":1}", existing.getRecord().getPayload());
    }

    @Test
    public void testGetByBusinessKeyReturnsMatchingTask() {
        JdbcLeaseBackend backend = new JdbcLeaseBackend(JdbcLeaseBackendTestSupport.newDataSource());
        LeasePublishResult publishResult = backend.publishIfAbsent(LeasePublishRequest.builder()
                .taskGroup("retry-q")
                .taskType("recover-payment")
                .payload("{\"attempt\":1}")
                .businessKey("recover-payment|order-2002")
                .attribute("traceId", "trace-1")
                .build());

        Optional<LeaseTaskRecord> result = backend.getByBusinessKey("retry-q", "recover-payment|order-2002");

        Assert.assertTrue(publishResult.isCreated());
        Assert.assertTrue(result.isPresent());
        Assert.assertEquals(publishResult.getTaskId(), result.get().getTaskId());
        Assert.assertEquals("recover-payment|order-2002", result.get().getBusinessKey());
        Assert.assertEquals("trace-1", result.get().getAttributes().get("traceId"));
    }
}
