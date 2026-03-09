package com.team4u.framework.retry.integration.lease;

import com.team4u.framework.lease.api.LeaseRuntimeClient;
import com.team4u.framework.lease.enums.LeaseRuntimeResult;
import com.team4u.framework.lease.model.LeaseCloseRequest;
import com.team4u.framework.lease.model.LeaseHandle;
import com.team4u.framework.lease.model.LeaseReleaseRequest;
import com.team4u.framework.lease.runtime.LeaseExecutionContext;
import com.team4u.framework.retry.backoff.Backoffs;
import com.team4u.framework.retry.client.RetryCoordinator;
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
import java.util.concurrent.atomic.AtomicBoolean;

public class RecoveryHandlerLeaseTaskHandlerAdapterTest {

    @Test
    public void testFailureUsesRuntimeReleaseWithUpdatedPayload() {
        RetryRecord record = retryRecord();
        TrackingRuntimeClient runtimeClient = new TrackingRuntimeClient();
        CountingCoordinator coordinator = new CountingCoordinator();
        RecoveryHandlerLeaseTaskHandlerAdapter adapter = new RecoveryHandlerLeaseTaskHandlerAdapter(
                new FailingHandler(),
                coordinator);
        FixedSerializer serializer = new FixedSerializer(record);
        adapter.setSerializer(serializer);

        adapter.handle(executionContext(runtimeClient));

        Assert.assertEquals(0, coordinator.scheduleCalls);
        Assert.assertNotNull(runtimeClient.releaseRequest);
        Assert.assertNull(runtimeClient.closeRequest);
        Assert.assertEquals(250L, runtimeClient.releaseRequest.getDelayMillis());
        Assert.assertEquals("serialized-1", runtimeClient.releaseRequest.getPayload());
        Assert.assertEquals("boom", runtimeClient.releaseRequest.getErrorMessage());
        Assert.assertEquals(1, record.getState().getAttempts());
        Assert.assertEquals(RetryStatus.SCHEDULED, record.getState().getStatus());
        Assert.assertEquals("RuntimeException", record.getState().getLastErrorCode());
        Assert.assertEquals("boom", record.getState().getLastErrorMessage());
        Assert.assertNotNull(record.getState().getNextRunAt());
    }

    @Test
    public void testRecoveryRunsInsideRecoveryExecutionContext() {
        TrackingRuntimeClient runtimeClient = new TrackingRuntimeClient();
        AtomicBoolean observedRecovering = new AtomicBoolean(false);
        RecoveryHandlerLeaseTaskHandlerAdapter adapter = new RecoveryHandlerLeaseTaskHandlerAdapter(
                new InspectingHandler(observedRecovering),
                new CountingCoordinator());
        adapter.setSerializer(new FixedSerializer(retryRecord()));

        adapter.handle(executionContext(runtimeClient));

        Assert.assertTrue(observedRecovering.get());
        Assert.assertFalse(RecoveryExecutionContext.isRecovering());
        Assert.assertNotNull(runtimeClient.closeRequest);
        Assert.assertNull(runtimeClient.releaseRequest);
        Assert.assertEquals(LeaseRuntimeResult.APPLIED, runtimeClient.closeResult);
    }

    private RetryRecord retryRecord() {
        return RetryRecord.builder()
                .taskId("task-1")
                .request(RetryRequest.builder()
                        .taskId("task-1")
                        .handlerTaskType("recover-payment")
                        .idempotencyKey("order-1")
                        .recovery(RecoverySpec.of("recover-payment", "payload"))
                        .policy(RetryPolicy.builder()
                                .maxAttempts(3)
                                .foregroundAttempts(1)
                                .backoff(Backoffs.fixed(250L))
                                .retryOn(RuntimeException.class)
                                .build())
                        .createdAt(Instant.now())
                        .build())
                .state(RetryState.builder()
                        .attempts(0)
                        .status(RetryStatus.RUNNING)
                        .build())
                .build();
    }

    private LeaseExecutionContext executionContext(TrackingRuntimeClient runtimeClient) {
        return LeaseExecutionContext.builder()
                .taskId("task-1")
                .queue(RetryLeaseQueues.DEFAULT_RECOVERY_QUEUE)
                .taskType("recover-payment")
                .payload("ignored")
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

    private static class CountingCoordinator implements RetryCoordinator {
        private int scheduleCalls;

        @Override
        public void schedule(RetryRecord record, long delayMillis) {
            scheduleCalls++;
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
        private LeaseReleaseRequest releaseRequest;
        private LeaseCloseRequest closeRequest;
        private final LeaseRuntimeResult closeResult = LeaseRuntimeResult.APPLIED;

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
            return LeaseRuntimeResult.APPLIED;
        }
    }
}
