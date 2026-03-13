package com.team4u.framework.retry.runtime.lease;

import com.team4u.framework.lease.api.LeaseAdminService;
import com.team4u.framework.lease.api.LeaseProducer;
import com.team4u.framework.lease.api.LeaseQueryService;
import com.team4u.framework.lease.enums.LeaseAdminResult;
import com.team4u.framework.lease.enums.LeaseTaskOutcome;
import com.team4u.framework.lease.enums.LeaseTaskState;
import com.team4u.framework.lease.model.*;
import com.team4u.framework.retry.api.RecoverySpec;
import com.team4u.framework.retry.managed.model.RetryRequest;
import com.team4u.framework.retry.managed.model.RetryState;
import com.team4u.framework.retry.managed.model.RetryStatus;
import com.team4u.framework.retry.managed.dispatch.DispatchResult;
import com.team4u.framework.retry.managed.dispatch.RetryDispatchCommand;
import com.team4u.framework.retry.managed.store.record.*;
import com.team4u.framework.retry.managed.store.serialize.RetryRecordSerializer;
import org.junit.Assert;
import org.junit.Test;

import java.time.Instant;
import java.util.Optional;

public class LeaseDurableRetryStoreTest {

    @Test
    public void testCreateIfAbsentUsesPublishIfAbsentWithBusinessKey() {
        RecordingLeaseApi api = new RecordingLeaseApi();
        LeaseDurableRetryStore store = new LeaseDurableRetryStore(
                api,
                api,
                api,
                "retry-q",
                new FixedSerializer("serialized", retryRecord("task-created", RetryStatus.ACCEPTED)));

        SubmitRecord result = store.createIfAbsent(RetryCreateRequest.builder()
                .request(RetryRequest.builder()
                        .taskType("payment")
                        .idempotencyKey("order-1")
                        .recovery(RecoverySpec.of("payment", "payload"))
                        .createdAt(Instant.now())
                        .build())
                .initialState(RetryState.builder().status(RetryStatus.ACCEPTED).attempts(0).build())
                .build());

        Assert.assertTrue(result.isCreated());
        Assert.assertEquals("task-created", result.getRecord().getTaskId());
        Assert.assertNotNull(api.publishRequest);
        Assert.assertEquals("payment|order-1", api.publishRequest.getBusinessKey());
        Assert.assertEquals("serialized", api.publishRequest.getPayload());
    }

    @Test
    public void testFindByIdempotencyKeyReadsFromLeaseBusinessKey() {
        RecordingLeaseApi api = new RecordingLeaseApi();
        api.queryResult = LeaseTaskRecord.builder()
                .taskId("task-existing")
                .taskGroup("retry-q")
                .taskType("payment")
                .payload("serialized-existing")
                .businessKey("payment|order-1")
                .state(LeaseTaskState.RUNNING)
                .build();
        LeaseDurableRetryStore store = new LeaseDurableRetryStore(
                api,
                api,
                api,
                "retry-q",
                new FixedSerializer("ignored", retryRecord("task-existing", RetryStatus.WAITING_RETRY)));

        Optional<RetryRecord> result = store.findByIdempotencyKey("payment", "order-1");

        Assert.assertTrue(result.isPresent());
        Assert.assertEquals(RetryStatus.PROCESSING, result.get().getState().getStatus());
        Assert.assertEquals("payment|order-1", api.businessKeyQueried);
    }

    @Test
    public void testDispatchUsesUpdateAndRescheduleWithUpdatedPayload() {
        RecordingLeaseApi api = new RecordingLeaseApi();
        api.getResult = LeaseTaskRecord.builder()
                .taskId("task-1")
                .taskGroup("retry-q")
                .taskType("payment")
                .payload("serialized-before")
                .state(LeaseTaskState.READY)
                .build();
        RetryRecord record = retryRecord("task-1", RetryStatus.ACCEPTED);
        FixedSerializer serializer = new FixedSerializer("serialized-after", record);
        LeaseDurableRetryStore store = new LeaseDurableRetryStore(api, api, api, "retry-q", serializer);

        DispatchResult result = store.dispatch(RetryDispatchCommand.builder()
                .record(record)
                .transition(RetryTransition.builder()
                        .attempts(2)
                        .nextRunAt(Instant.now().plusSeconds(5))
                        .lastErrorCode("IOException")
                        .lastErrorMessage("boom")
                        .build())
                .delayMillis(500L)
                .build());

        Assert.assertEquals("task-1", result.getTaskId());
        Assert.assertEquals("task-1", result.getBackendTaskId());
        Assert.assertNotNull(api.updateRequest);
        Assert.assertEquals("serialized-after", api.updateRequest.getPayload());
        Assert.assertEquals(500L, api.delayMillis);
        Assert.assertEquals(RetryStatus.WAITING_RETRY, serializer.record.getState().getStatus());
    }

    @Test
    public void testMarkSucceededClosesTaskWithUpdatedPayload() {
        RecordingLeaseApi api = new RecordingLeaseApi();
        api.getResult = LeaseTaskRecord.builder()
                .taskId("task-1")
                .taskGroup("retry-q")
                .taskType("payment")
                .payload("serialized-before")
                .state(LeaseTaskState.READY)
                .build();
        RetryRecord record = retryRecord("task-1", RetryStatus.ACCEPTED);
        LeaseDurableRetryStore store = new LeaseDurableRetryStore(
                api,
                api,
                api,
                "retry-q",
                new FixedSerializer("serialized-success", record));
        Instant succeededAt = Instant.now();

        store.markSucceeded("task-1", SuccessRecord.builder().succeededAt(succeededAt).build());

        Assert.assertNotNull(api.closeRequest);
        Assert.assertEquals(LeaseTaskOutcome.SUCCEEDED, api.closeRequest.getOutcome());
        Assert.assertEquals("serialized-success", api.closeRequest.getPayload());
        Assert.assertEquals(succeededAt, record.getState().getSucceededAt());
    }

    @Test
    public void testMarkFailedPersistsFailureDetails() {
        RecordingLeaseApi api = new RecordingLeaseApi();
        api.getResult = LeaseTaskRecord.builder()
                .taskId("task-1")
                .taskGroup("retry-q")
                .taskType("payment")
                .payload("serialized-before")
                .state(LeaseTaskState.READY)
                .build();
        RetryRecord record = retryRecord("task-1", RetryStatus.ACCEPTED);
        LeaseDurableRetryStore store = new LeaseDurableRetryStore(
                api,
                api,
                api,
                "retry-q",
                new FixedSerializer("serialized-failed", record));
        Instant failedAt = Instant.now();

        store.markFailed("task-1", FailureRecord.builder()
                .errorCode("IOException")
                .errorMessage("boom")
                .failedAt(failedAt)
                .build());

        Assert.assertEquals(LeaseTaskOutcome.FAILED, api.closeRequest.getOutcome());
        Assert.assertEquals("IOException", record.getState().getLastErrorCode());
        Assert.assertEquals("boom", record.getState().getLastErrorMessage());
        Assert.assertEquals(failedAt, record.getState().getFailedAt());
    }

    @Test
    public void testMarkCancelledPersistsCancelDetails() {
        RecordingLeaseApi api = new RecordingLeaseApi();
        api.getResult = LeaseTaskRecord.builder()
                .taskId("task-1")
                .taskGroup("retry-q")
                .taskType("payment")
                .payload("serialized-before")
                .state(LeaseTaskState.READY)
                .build();
        RetryRecord record = retryRecord("task-1", RetryStatus.ACCEPTED);
        LeaseDurableRetryStore store = new LeaseDurableRetryStore(
                api,
                api,
                api,
                "retry-q",
                new FixedSerializer("serialized-cancelled", record));
        Instant cancelledAt = Instant.now();

        store.markCancelled("task-1", CancelRecord.builder()
                .reason("manual stop")
                .cancelledAt(cancelledAt)
                .build());

        Assert.assertEquals(LeaseTaskOutcome.CANCELLED, api.closeRequest.getOutcome());
        Assert.assertEquals("manual stop", record.getState().getLastErrorMessage());
        Assert.assertEquals(cancelledAt, record.getState().getCancelledAt());
    }

    @Test(expected = NullPointerException.class)
    public void testMarkSucceededRejectsNullSuccessRecord() {
        RecordingLeaseApi api = new RecordingLeaseApi();
        api.getResult = LeaseTaskRecord.builder()
                .taskId("task-1")
                .taskGroup("retry-q")
                .taskType("payment")
                .payload("serialized-before")
                .state(LeaseTaskState.READY)
                .build();
        LeaseDurableRetryStore store = new LeaseDurableRetryStore(api, api, api, "retry-q");

        store.markSucceeded("task-1", null);
    }

    private RetryRecord retryRecord(String taskId, RetryStatus status) {
        return RetryRecord.builder()
                .taskId(taskId)
                .request(RetryRequest.builder()
                        .taskId(taskId)
                        .taskType("payment")
                        .idempotencyKey("order-1")
                        .recovery(RecoverySpec.of("payment", "payload"))
                        .createdAt(Instant.now())
                        .build())
                .state(RetryState.builder()
                        .attempts(0)
                        .status(status)
                        .build())
                .build();
    }

    private static class FixedSerializer implements RetryRecordSerializer {
        private final String value;
        private final RetryRecord record;

        private FixedSerializer(String value, RetryRecord record) {
            this.value = value;
            this.record = record;
        }

        @Override
        public String serialize(RetryRecord record) {
            this.record.setState(record.getState());
            return value;
        }

        @Override
        public RetryRecord deserialize(String data) {
            return record;
        }
    }

    private static class RecordingLeaseApi implements LeaseProducer, LeaseAdminService, LeaseQueryService {
        private LeasePublishRequest publishRequest;
        private LeaseUpdateRequest updateRequest;
        private LeaseCloseRequest closeRequest;
        private long delayMillis;
        private LeaseTaskRecord getResult;
        private LeaseTaskRecord queryResult;
        private String businessKeyQueried;

        @Override
        public String publish(LeasePublishRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LeasePublishResult publishIfAbsent(LeasePublishRequest request) {
            publishRequest = request;
            return LeasePublishResult.builder()
                    .created(true)
                    .taskId("task-created")
                    .record(null)
                    .build();
        }

        @Override
        public LeaseAdminResult reschedule(String taskId, long delayMillis) {
            return LeaseAdminResult.APPLIED;
        }

        @Override
        public LeaseAdminResult close(String taskId, LeaseCloseRequest request) {
            closeRequest = request;
            return LeaseAdminResult.APPLIED;
        }

        @Override
        public LeaseAdminResult rescheduleFailed(String taskId, long delayMillis) {
            return LeaseAdminResult.APPLIED;
        }

        @Override
        public LeaseAdminResult update(LeaseUpdateRequest request) {
            updateRequest = request;
            return LeaseAdminResult.APPLIED;
        }

        @Override
        public LeaseAdminResult updateAndReschedule(LeaseUpdateRequest request, long delayMillis) {
            updateRequest = request;
            this.delayMillis = delayMillis;
            return LeaseAdminResult.APPLIED;
        }

        @Override
        public Optional<LeaseTaskRecord> get(String taskId) {
            return Optional.ofNullable(getResult);
        }

        @Override
        public Optional<LeaseTaskRecord> getByBusinessKey(String taskGroup, String businessKey) {
            businessKeyQueried = businessKey;
            return Optional.ofNullable(queryResult);
        }

        @Override
        public LeaseTaskPage list(LeaseQueryRequest request) {
            return LeaseTaskPage.builder().total(0).page(0).pageSize(0).build();
        }
    }
}
