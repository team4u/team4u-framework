package com.team4u.framework.retry.integration.lease;

import com.team4u.framework.lease.handler.LeaseTaskHandler;
import com.team4u.framework.lease.runtime.LeaseExecutionContext;
import com.team4u.framework.retry.managed.recovery.RecoveryContext;
import com.team4u.framework.retry.managed.recovery.RecoveryHandler;
import com.team4u.framework.retry.managed.recovery.RecoveryHandlerRegistry;
import org.junit.Assert;
import org.junit.Test;

public class RecoveryHandlerRegistryLeaseAdapterTest {

    @Test
    public void testRegisterLeaseTaskHandlerPreservesStringPayload() throws Exception {
        RecoveryHandlerRegistry registry = new RecoveryHandlerRegistry();
        RecoveryHandlerRegistryLeaseAdapter adapter = new RecoveryHandlerRegistryLeaseAdapter(registry);
        CapturingLeaseTaskHandler handler = new CapturingLeaseTaskHandler();

        adapter.register(RetryLeaseQueues.DEFAULT_RECOVERY_QUEUE, "payment", handler);

        @SuppressWarnings("rawtypes")
        RecoveryHandler recoveryHandler = registry.get("payment").orElseThrow(AssertionError::new);
        recoveryHandler.recover("payload-1", RecoveryContext.builder().taskId("task-1").attempt(1).build());

        Assert.assertEquals("task-1", handler.context.getTaskId());
        Assert.assertEquals("payment", handler.context.getTaskType());
        Assert.assertEquals("payload-1", handler.context.getPayload());
    }

    @Test
    public void testGetRejectsNonStringRecoveryHandler() {
        RecoveryHandlerRegistry registry = new RecoveryHandlerRegistry();
        RecoveryHandlerRegistryLeaseAdapter adapter = new RecoveryHandlerRegistryLeaseAdapter(registry);
        registry.register(new ObjectPayloadRecoveryHandler());
        try {
            adapter.get(RetryLeaseQueues.DEFAULT_RECOVERY_QUEUE, "payment");
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            Assert.assertTrue(ex.getMessage().contains("StringRecoveryHandler"));
            Assert.assertTrue(ex.getMessage().contains("taskType=payment"));
            Assert.assertTrue(ex.getMessage().contains(ObjectPayloadRecoveryHandler.class.getName()));
        }
    }

    @Test
    public void testAdapterRejectsNonStringRecoveryHandlerAtConstruction() {
        try {
            new RecoveryHandlerLeaseTaskHandlerAdapter(null);
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            Assert.assertTrue(ex.getMessage().contains("StringRecoveryHandler"));
        }
    }

    private static class CapturingLeaseTaskHandler implements LeaseTaskHandler {
        private LeaseExecutionContext context;

        @Override
        public void handle(LeaseExecutionContext context) {
            this.context = context;
        }
    }

    private static class ObjectPayloadRecoveryHandler implements RecoveryHandler<Object> {
        @Override
        public String taskName() {
            return "payment";
        }

        @Override
        public void recover(Object payload, RecoveryContext context) {
        }
    }
}
