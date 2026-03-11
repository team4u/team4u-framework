package com.team4u.framework.retry.integration.lease;

import com.team4u.framework.lease.api.LeaseRuntimeClient;
import com.team4u.framework.lease.enums.LeaseRuntimeResult;
import com.team4u.framework.lease.model.LeaseCloseRequest;
import com.team4u.framework.lease.model.LeaseHandle;
import com.team4u.framework.lease.model.LeaseReleaseRequest;
import com.team4u.framework.lease.runtime.LeaseExecutionContext;
import com.team4u.framework.retry.backoff.Backoffs;
import com.team4u.framework.retry.domain.RecoverySpec;
import com.team4u.framework.retry.domain.store.RetryRequest;
import com.team4u.framework.retry.domain.store.RetryState;
import com.team4u.framework.retry.domain.store.RetryStatus;
import com.team4u.framework.retry.policy.RetryPolicy;
import com.team4u.framework.retry.recovery.RecoveryContext;
import com.team4u.framework.retry.recovery.RecoveryExecutionContext;
import com.team4u.framework.retry.recovery.RecoveryHandler;
import com.team4u.framework.retry.store.record.RetryRecord;
import com.team4u.framework.retry.store.serialize.RetryRecordSerializer;
import org.junit.Assert;
import org.junit.Test;

import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public class RecoveryHandlerLeaseTaskHandlerAdapterTest {

    @Test
    public void testFailureUsesRuntimeReleaseWithUpdatedPayload() {
        RetryRecord record = retryRecord();
        TrackingRuntimeClient runtimeClient = new TrackingRuntimeClient();
        RecoveryHandlerLeaseTaskHandlerAdapter adapter = new RecoveryHandlerLeaseTaskHandlerAdapter(new FailingHandler());
        FixedSerializer serializer = new FixedSerializer(record);
        adapter.setSerializer(serializer);
        LeaseExecutionContext context = executionContext(runtimeClient);

        adapter.handle(context);

        Assert.assertNotNull(runtimeClient.releaseRequest);
        Assert.assertNull(runtimeClient.closeRequest);
        Assert.assertEquals(250L, runtimeClient.releaseRequest.getDelayMillis());
        Assert.assertEquals("serialized-1", runtimeClient.releaseRequest.getPayload());
        Assert.assertEquals("boom", runtimeClient.releaseRequest.getErrorMessage());
        Assert.assertEquals(1, record.getState().getAttempts());
        Assert.assertEquals(RetryStatus.WAITING_RETRY, record.getState().getStatus());
        Assert.assertEquals("RuntimeException", record.getState().getLastErrorCode());
        Assert.assertEquals("boom", record.getState().getLastErrorMessage());
        Assert.assertNotNull(record.getState().getNextRunAt());
        Assert.assertTrue(context.isLifecycleHandled());
    }

    @Test
    public void testRecoveryRunsInsideRecoveryExecutionContext() {
        TrackingRuntimeClient runtimeClient = new TrackingRuntimeClient();
        AtomicBoolean observedRecovering = new AtomicBoolean(false);
        RecoveryHandlerLeaseTaskHandlerAdapter adapter =
                new RecoveryHandlerLeaseTaskHandlerAdapter(new InspectingHandler(observedRecovering));
        adapter.setSerializer(new FixedSerializer(retryRecord()));

        adapter.handle(executionContext(runtimeClient));

        Assert.assertTrue(observedRecovering.get());
        Assert.assertFalse(RecoveryExecutionContext.isRecovering());
        Assert.assertNotNull(runtimeClient.closeRequest);
        Assert.assertNull(runtimeClient.releaseRequest);
        Assert.assertEquals(LeaseRuntimeResult.APPLIED, runtimeClient.closeResult);
        Assert.assertEquals("serialized-1", runtimeClient.closeRequest.getPayload());
    }

    @Test
    public void testTerminalFailureClosesWithUpdatedPayload() {
        RetryRecord record = retryRecord();
        record.getRequest().setPolicy(RetryPolicy.builder()
                .maxRetries(0)
                .foregroundMaxRetries(0)
                .backoff(Backoffs.fixed(0L))
                .retryOn(RuntimeException.class)
                .build());
        TrackingRuntimeClient runtimeClient = new TrackingRuntimeClient();
        RecoveryHandlerLeaseTaskHandlerAdapter adapter = new RecoveryHandlerLeaseTaskHandlerAdapter(new FailingHandler());
        adapter.setSerializer(new FixedSerializer(record));

        adapter.handle(executionContext(runtimeClient));

        Assert.assertNull(runtimeClient.releaseRequest);
        Assert.assertNotNull(runtimeClient.closeRequest);
        Assert.assertEquals("serialized-1", runtimeClient.closeRequest.getPayload());
        Assert.assertEquals("boom", runtimeClient.closeRequest.getErrorMessage());
    }

    @Test
    public void testReleaseThrowsWhenRuntimeMutationNotApplied() {
        RetryRecord record = retryRecord();
        TrackingRuntimeClient runtimeClient = new TrackingRuntimeClient();
        runtimeClient.releaseResult = LeaseRuntimeResult.LEASE_LOST;
        RecoveryHandlerLeaseTaskHandlerAdapter adapter = new RecoveryHandlerLeaseTaskHandlerAdapter(new FailingHandler());
        adapter.setSerializer(new FixedSerializer(record));

        try {
            adapter.handle(executionContext(runtimeClient));
            Assert.fail("expected IllegalStateException");
        } catch (IllegalStateException ex) {
            Assert.assertTrue(ex.getMessage().contains("release"));
            Assert.assertTrue(ex.getMessage().contains("LEASE_LOST"));
        }
    }

    @Test
    public void testInterruptedFailureClosesAndPreservesInterruptFlag() {
        RetryRecord record = retryRecord();
        TrackingRuntimeClient runtimeClient = new TrackingRuntimeClient();
        RecoveryHandlerLeaseTaskHandlerAdapter adapter =
                new RecoveryHandlerLeaseTaskHandlerAdapter(new InterruptedHandler());
        adapter.setSerializer(new FixedSerializer(record));

        try {
            adapter.handle(executionContext(runtimeClient));
            Assert.assertNull(runtimeClient.releaseRequest);
            Assert.assertNotNull(runtimeClient.closeRequest);
            Assert.assertEquals("stop", runtimeClient.closeRequest.getErrorMessage());
            Assert.assertEquals(RetryStatus.FAILED, record.getState().getStatus());
            Assert.assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private RetryRecord retryRecord() {
        return RetryRecord.builder()
                .taskId("task-1")
                .request(RetryRequest.builder()
                        .taskId("task-1")
                        .taskType("recover-payment")
                        .idempotencyKey("order-1")
                        .recovery(RecoverySpec.of("recover-payment", "payload"))
                        .policy(RetryPolicy.builder()
                                .maxRetries(2)
                                .foregroundMaxRetries(0)
                                .backoff(Backoffs.fixed(250L))
                                .retryOn(RuntimeException.class)
                                .build())
                        .createdAt(Instant.now())
                        .build())
                .state(RetryState.builder()
                        .attempts(0)
                        .status(RetryStatus.WAITING_RETRY)
                        .nextRunAt(Instant.now().plusSeconds(30))
                        .build())
                .build();
    }

    private LeaseExecutionContext executionContext(TrackingRuntimeClient runtimeClient) {
        return LeaseExecutionContext.builder()
                .taskId("task-1")
                .queue(RetryLeaseQueues.DEFAULT_RECOVERY_QUEUE)
                .taskType("recover-payment")
                .payload("serialized-input")
                .runtimeClient(runtimeClient)
                .handle(new LeaseHandle("task-1", "worker-1", "lease-1"))
                .build();
    }

    private static class FailingHandler implements RecoveryHandler<String> {
        @Override
        public String taskName() {
            return "recover-payment";
        }

        @Override
        public void recover(String payload, RecoveryContext context) {
            throw new RuntimeException("boom");
        }
    }

    private static class InspectingHandler implements RecoveryHandler<String> {
        private final AtomicBoolean observedRecovering;

        private InspectingHandler(AtomicBoolean observedRecovering) {
            this.observedRecovering = observedRecovering;
        }

        @Override
        public String taskName() {
            return "recover-payment";
        }

        @Override
        public void recover(String payload, RecoveryContext context) {
            observedRecovering.set(RecoveryExecutionContext.isRecovering());
        }
    }

    private static class InterruptedHandler implements RecoveryHandler<String> {
        @Override
        public String taskName() {
            return "recover-payment";
        }

        @Override
        public void recover(String payload, RecoveryContext context) throws Exception {
            throw new ExecutionException(new InterruptedException("stop"));
        }
    }

    private static class FixedSerializer implements RetryRecordSerializer {
        private final RetryRecord record;
        private int serializeCalls;

        private FixedSerializer(RetryRecord record) {
            this.record = record;
        }

        @Override
        public String serialize(RetryRecord record) {
            serializeCalls++;
            return "serialized-" + serializeCalls;
        }

        @Override
        public RetryRecord deserialize(String data) {
            return record;
        }
    }

    private static class TrackingRuntimeClient implements LeaseRuntimeClient {
        private final LeaseRuntimeResult closeResult = LeaseRuntimeResult.APPLIED;
        private LeaseRuntimeResult releaseResult = LeaseRuntimeResult.APPLIED;
        private LeaseReleaseRequest releaseRequest;
        private LeaseCloseRequest closeRequest;

        @Override
        public com.team4u.framework.lease.model.LeaseGrant acquire(
                com.team4u.framework.lease.model.LeaseAcquireRequest request) {
            return null;
        }

        @Override
        public LeaseRuntimeResult close(LeaseHandle handle, LeaseCloseRequest request) {
            closeRequest = request;
            return closeResult;
        }

        @Override
        public LeaseRuntimeResult heartbeat(LeaseHandle handle, long extendMillis) {
            return LeaseRuntimeResult.APPLIED;
        }

        @Override
        public LeaseRuntimeResult release(LeaseHandle handle, LeaseReleaseRequest request) {
            releaseRequest = request;
            return releaseResult;
        }
    }
}
