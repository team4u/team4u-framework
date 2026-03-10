package com.team4u.framework.retry.integration.lease;

import com.team4u.framework.lease.handler.LeaseTaskHandler;
import com.team4u.framework.lease.runtime.LeaseExecutionContext;
import com.team4u.framework.retry.recovery.RecoveryContext;
import com.team4u.framework.retry.recovery.RecoveryHandler;
import com.team4u.framework.retry.recovery.RecoveryHandlerRegistry;
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
    public void testRegisterLeaseTaskHandlerRejectsNonStringPayload() throws Exception {
        RecoveryHandlerRegistry registry = new RecoveryHandlerRegistry();
        RecoveryHandlerRegistryLeaseAdapter adapter = new RecoveryHandlerRegistryLeaseAdapter(registry);
        CapturingLeaseTaskHandler handler = new CapturingLeaseTaskHandler();

        adapter.register(RetryLeaseQueues.DEFAULT_RECOVERY_QUEUE, "payment", handler);

        @SuppressWarnings("rawtypes")
        RecoveryHandler recoveryHandler = registry.get("payment").orElseThrow(AssertionError::new);
        try {
            recoveryHandler.recover(new Object(), RecoveryContext.builder().taskId("task-2").attempt(1).build());
            Assert.fail("expected ClassCastException");
        } catch (ClassCastException ex) {
            Assert.assertNotNull(ex.getMessage());
        }
        Assert.assertNull(handler.context);
    }

    private static class CapturingLeaseTaskHandler implements LeaseTaskHandler {
        private LeaseExecutionContext context;

        @Override
        public void handle(LeaseExecutionContext context) {
            this.context = context;
        }
    }
}
