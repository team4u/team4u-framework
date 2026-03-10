package com.team4u.framework.retry.integration.lease;

import com.team4u.framework.lease.api.LeaseAdminService;
import com.team4u.framework.lease.api.LeaseBackend;
import com.team4u.framework.lease.api.LeaseProducer;
import com.team4u.framework.lease.enums.LeaseAdminResult;
import com.team4u.framework.lease.enums.LeaseRuntimeResult;
import com.team4u.framework.lease.enums.LeaseTaskFailureReason;
import com.team4u.framework.lease.enums.LeaseTaskOutcome;
import com.team4u.framework.lease.model.*;
import com.team4u.framework.retry.domain.store.RetryRequest;
import com.team4u.framework.retry.domain.store.RetryState;
import com.team4u.framework.retry.domain.store.RetryStatus;
import com.team4u.framework.retry.store.record.CancelRecord;
import com.team4u.framework.retry.store.record.FailureRecord;
import com.team4u.framework.retry.store.record.RetryRecord;
import com.team4u.framework.retry.store.record.SuccessRecord;
import com.team4u.framework.retry.store.serialize.RetryRecordSerializer;
import org.junit.Assert;
import org.junit.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class LeaseDurableRetryStoreTest {

    private static RetryRecord retryRecord(String taskType) {
        return RetryRecord.builder()
                .request(RetryRequest.builder()
                        .taskType(taskType)
                        .taskId("request-" + taskType)
                        .createdAt(Instant.now())
                        .build())
                .state(RetryState.builder()
                        .attempts(0)
                        .status(RetryStatus.PREPARED)
                        .build())
                .build();
    }

    /**
     * 验证 create 会使用默认恢复队列发布一个长延迟的 prepared 任务。
     */
    @Test
    public void testCreatePublishesPreparedTaskWithDefaultQueue() {
        RecordingBackend backend = new RecordingBackend();
        LeaseDurableRetryStore store = new LeaseDurableRetryStore(backend);
        FixedSerializer serializer = new FixedSerializer("serialized-create");
        RetryRecord record = retryRecord("payment");

        store.setSerializer(serializer);
        String taskId = store.create(record);

        Assert.assertEquals("task-created", taskId);
        Assert.assertSame(record, serializer.lastSerializedRecord);
        Assert.assertNotNull(backend.publishRequest);
        Assert.assertEquals(RetryLeaseQueues.DEFAULT_RECOVERY_QUEUE, backend.publishRequest.getQueue());
        Assert.assertEquals("payment", backend.publishRequest.getTaskType());
        Assert.assertEquals("serialized-create", backend.publishRequest.getPayload());
        Assert.assertEquals(315360000000L, backend.publishRequest.getDelayMillis());
    }

    /**
     * 验证空白队列配置会回落到默认恢复队列。
     */
    @Test
    public void testCreateFallsBackToDefaultQueueWhenQueueBlank() {
        RecordingProducer producer = new RecordingProducer();
        RecordingAdminService adminService = new RecordingAdminService();
        LeaseDurableRetryStore store = new LeaseDurableRetryStore(producer, adminService, "  ");
        RetryRecord record = retryRecord("refund");

        store.setSerializer(new FixedSerializer("payload"));
        store.create(record);

        Assert.assertEquals(RetryLeaseQueues.DEFAULT_RECOVERY_QUEUE, producer.publishRequest.getQueue());
    }

    /**
     * 验证 markSucceeded 会向管理端写入成功关闭请求。
     */
    @Test
    public void testMarkSucceededClosesTaskAsSucceeded() {
        RecordingProducer producer = new RecordingProducer();
        RecordingAdminService adminService = new RecordingAdminService();
        LeaseDurableRetryStore store = new LeaseDurableRetryStore(producer, adminService, "retry-q");

        store.markSucceeded("task-1", SuccessRecord.builder().succeededAt(Instant.now()).build());

        Assert.assertEquals("task-1", adminService.closedTaskId);
        Assert.assertNotNull(adminService.closeRequest);
        Assert.assertEquals(LeaseTaskOutcome.SUCCEEDED, adminService.closeRequest.getOutcome());
        Assert.assertNull(adminService.closeRequest.getFailureReason());
        Assert.assertNull(adminService.closeRequest.getErrorMessage());
    }

    /**
     * 验证 markFailed 会以 RETRY_EXHAUSTED 原因关闭任务。
     */
    @Test
    public void testMarkFailedClosesTaskAsRetryExhausted() {
        RecordingProducer producer = new RecordingProducer();
        RecordingAdminService adminService = new RecordingAdminService();
        LeaseDurableRetryStore store = new LeaseDurableRetryStore(producer, adminService, "retry-q");
        FailureRecord failure = FailureRecord.builder()
                .errorCode("E100")
                .errorMessage("boom")
                .failedAt(Instant.now())
                .build();

        store.markFailed("task-2", failure);

        Assert.assertEquals("task-2", adminService.closedTaskId);
        Assert.assertEquals(LeaseTaskOutcome.FAILED, adminService.closeRequest.getOutcome());
        Assert.assertEquals(LeaseTaskFailureReason.RETRY_EXHAUSTED, adminService.closeRequest.getFailureReason());
        Assert.assertEquals("boom", adminService.closeRequest.getErrorMessage());
    }

    /**
     * 验证 cancel 会写入取消结果和取消原因。
     */
    @Test
    public void testCancelClosesTaskAsCancelled() {
        RecordingProducer producer = new RecordingProducer();
        RecordingAdminService adminService = new RecordingAdminService();
        LeaseDurableRetryStore store = new LeaseDurableRetryStore(producer, adminService, "retry-q");
        CancelRecord cancel = CancelRecord.builder()
                .reason("user-request")
                .cancelledAt(Instant.now())
                .build();

        store.cancel("task-3", cancel);

        Assert.assertEquals("task-3", adminService.closedTaskId);
        Assert.assertEquals(LeaseTaskOutcome.CANCELLED, adminService.closeRequest.getOutcome());
        Assert.assertNull(adminService.closeRequest.getFailureReason());
        Assert.assertEquals("user-request", adminService.closeRequest.getErrorMessage());
    }

    /**
     * 验证 schedule 会通过单次原子管理操作更新 payload 并重新调度。
     */
    @Test
    public void testScheduleUsesAtomicUpdateAndReschedule() {
        RecordingProducer producer = new RecordingProducer();
        RecordingAdminService adminService = new RecordingAdminService();
        LeaseDurableRetryStore store = new LeaseDurableRetryStore(producer, adminService, "retry-q");
        FixedSerializer serializer = new FixedSerializer("serialized-schedule");
        RetryRecord record = retryRecord("payment");
        record.setTaskId("task-4");
        record.getState().setAttempts(2);
        record.getState().setStatus(RetryStatus.SCHEDULED);
        record.getState().setLastErrorCode("IOException");
        record.getState().setLastErrorMessage("boom");
        record.getState().setNextRunAt(Instant.now().plusSeconds(5));

        store.setSerializer(serializer);
        store.schedule(record, 500L);

        Assert.assertSame(record, serializer.lastSerializedRecord);
        Assert.assertEquals(2, serializer.lastSerializedRecord.getState().getAttempts());
        Assert.assertEquals(RetryStatus.SCHEDULED, serializer.lastSerializedRecord.getState().getStatus());
        Assert.assertEquals("IOException", serializer.lastSerializedRecord.getState().getLastErrorCode());
        Assert.assertEquals("boom", serializer.lastSerializedRecord.getState().getLastErrorMessage());
        Assert.assertNotNull(serializer.lastSerializedRecord.getState().getNextRunAt());
        Assert.assertEquals(1, adminService.operations.size());
        Assert.assertEquals("updateAndReschedule", adminService.operations.get(0));
        Assert.assertNotNull(adminService.updateRequest);
        Assert.assertEquals("task-4", adminService.updateRequest.getTaskId());
        Assert.assertEquals("serialized-schedule", adminService.updateRequest.getPayload());
        Assert.assertEquals("task-4", adminService.updateAndRescheduleTaskId);
        Assert.assertEquals(500L, adminService.updateAndRescheduleDelayMillis);
    }

    /**
     * 验证原子更新未生效时，schedule 会立即抛出带可恢复语义的异常。
     */
    @Test
    public void testScheduleThrowsWhenAtomicUpdateNotApplied() {
        RecordingProducer producer = new RecordingProducer();
        RecordingAdminService adminService = new RecordingAdminService();
        adminService.updateAndRescheduleResult = LeaseAdminResult.ACTIVE_LEASE_PRESENT;
        LeaseDurableRetryStore store = new LeaseDurableRetryStore(producer, adminService, "retry-q");
        RetryRecord record = retryRecord("payment");
        record.setTaskId("task-5");

        store.setSerializer(new FixedSerializer("payload"));
        try {
            store.schedule(record, 100L);
            Assert.fail("expected LeaseAdminOperationException");
        } catch (LeaseAdminOperationException ex) {
            Assert.assertEquals("updateAndSchedule", ex.getOperation());
            Assert.assertTrue(ex.isRetriable());
            Assert.assertTrue(ex.getMessage().contains("task-5"));
        }

        Assert.assertEquals(1, adminService.operations.size());
        Assert.assertEquals("updateAndReschedule", adminService.operations.get(0));
    }

    /**
     * 验证 close 未生效时，最终失败写入会抛出异常。
     */
    @Test
    public void testMarkFailedThrowsWhenCloseNotApplied() {
        RecordingProducer producer = new RecordingProducer();
        RecordingAdminService adminService = new RecordingAdminService();
        adminService.closeResult = LeaseAdminResult.CLOSED;
        LeaseDurableRetryStore store = new LeaseDurableRetryStore(producer, adminService, "retry-q");

        try {
            store.markFailed("task-6", FailureRecord.builder().errorMessage("boom").build());
            Assert.fail("expected IllegalStateException");
        } catch (IllegalStateException ex) {
            Assert.assertTrue(ex.getMessage().contains("closeFailed"));
            Assert.assertTrue(ex.getMessage().contains("task-6"));
        }
    }

    /**
     * 固定返回给定字符串，并记录最近一次序列化入参，便于断言请求拼装。
     */
    private static class FixedSerializer implements RetryRecordSerializer {

        private final String value;
        private RetryRecord lastSerializedRecord;

        private FixedSerializer(String value) {
            this.value = value;
        }

        @Override
        public String serialize(RetryRecord record) {
            lastSerializedRecord = record;
            return value;
        }

        @Override
        public RetryRecord deserialize(String data) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * 记录发布请求的最小 Producer 实现。
     */
    private static class RecordingProducer implements LeaseProducer {

        private LeasePublishRequest publishRequest;

        @Override
        public String publish(LeasePublishRequest request) {
            publishRequest = request;
            return "task-created";
        }
    }

    /**
     * 记录管理端调用顺序与参数的最小 AdminService 实现。
     */
    private static class RecordingAdminService implements LeaseAdminService {

        private final List<String> operations = new ArrayList<String>();
        private LeaseAdminResult closeResult = LeaseAdminResult.APPLIED;
        private LeaseAdminResult updateResult = LeaseAdminResult.APPLIED;
        private LeaseAdminResult updateAndRescheduleResult = LeaseAdminResult.APPLIED;
        private String updateAndRescheduleTaskId;
        private long updateAndRescheduleDelayMillis;
        private String closedTaskId;
        private LeaseCloseRequest closeRequest;
        private LeaseUpdateRequest updateRequest;

        @Override
        public LeaseAdminResult reschedule(String taskId, long delayMillis) {
            operations.add("reschedule");
            return LeaseAdminResult.APPLIED;
        }

        @Override
        public LeaseAdminResult close(String taskId, LeaseCloseRequest request) {
            operations.add("close");
            closedTaskId = taskId;
            closeRequest = request;
            return closeResult;
        }

        @Override
        public LeaseAdminResult requeueFailed(String taskId, long delayMillis) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LeaseAdminResult update(LeaseUpdateRequest request) {
            operations.add("update");
            updateRequest = request;
            return updateResult;
        }

        @Override
        public LeaseAdminResult updateAndReschedule(LeaseUpdateRequest request, long delayMillis) {
            operations.add("updateAndReschedule");
            updateRequest = request;
            updateAndRescheduleTaskId = request.getTaskId();
            updateAndRescheduleDelayMillis = delayMillis;
            return updateAndRescheduleResult;
        }
    }

    /**
     * 同时实现 producer/admin/runtime/query 的测试后端，便于覆盖默认构造路径。
     */
    private static class RecordingBackend implements LeaseBackend {

        private final List<String> operations = new ArrayList<String>();
        private LeasePublishRequest publishRequest;

        @Override
        public String publish(LeasePublishRequest request) {
            publishRequest = request;
            return "task-created";
        }

        @Override
        public LeaseAdminResult reschedule(String taskId, long delayMillis) {
            operations.add("reschedule");
            return LeaseAdminResult.APPLIED;
        }

        @Override
        public LeaseAdminResult close(String taskId, LeaseCloseRequest request) {
            operations.add("close");
            return LeaseAdminResult.APPLIED;
        }

        @Override
        public LeaseAdminResult requeueFailed(String taskId, long delayMillis) {
            operations.add("requeueFailed");
            return LeaseAdminResult.APPLIED;
        }

        @Override
        public LeaseAdminResult update(LeaseUpdateRequest request) {
            operations.add("update");
            return LeaseAdminResult.APPLIED;
        }

        @Override
        public LeaseAdminResult updateAndReschedule(LeaseUpdateRequest request, long delayMillis) {
            operations.add("updateAndReschedule");
            return LeaseAdminResult.APPLIED;
        }

        @Override
        public LeaseGrant acquire(LeaseAcquireRequest request) {
            return null;
        }

        @Override
        public LeaseRuntimeResult close(LeaseHandle handle, LeaseCloseRequest request) {
            return LeaseRuntimeResult.APPLIED;
        }

        @Override
        public LeaseRuntimeResult heartbeat(LeaseHandle handle, long extendMillis) {
            return LeaseRuntimeResult.APPLIED;
        }

        @Override
        public LeaseRuntimeResult release(LeaseHandle handle, LeaseReleaseRequest request) {
            return LeaseRuntimeResult.APPLIED;
        }

        @Override
        public Optional<LeaseTaskRecord> get(String taskId) {
            return Optional.empty();
        }

        @Override
        public LeaseTaskPage list(LeaseQueryRequest request) {
            return LeaseTaskPage.builder().total(0).page(0).pageSize(0).build();
        }
    }
}
