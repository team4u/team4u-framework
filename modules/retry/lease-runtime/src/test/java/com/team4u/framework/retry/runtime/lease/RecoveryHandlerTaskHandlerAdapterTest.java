package com.team4u.framework.retry.runtime.lease;

import com.team4u.framework.lease.api.TaskContext;
import com.team4u.framework.lease.api.TaskResult;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.managed.model.RetryStatus;
import com.team4u.framework.retry.managed.recovery.RecoveryContext;
import com.team4u.framework.retry.managed.recovery.StringRecoveryHandler;
import com.team4u.framework.retry.managed.store.record.RetryRecord;
import com.team4u.framework.retry.managed.store.serialize.RetryRecordSerializer;
import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class RecoveryHandlerTaskHandlerAdapterTest {

    private final LeaseRetryRecordSerializer serializer = LeaseRetryRecordSerializer.INSTANCE;

    @Test
    public void testSuccessReturnsSuccessTaskResult() throws Exception {
        RecordingHandler handler = new RecordingHandler("payment", 0);
        RetryRecord record = durableRecord(1, RetryLeaseTestSupport.retryPolicy(2, 10L));
        TaskResult result = new RecoveryHandlerTaskHandlerAdapter(handler, serializer)
                .handle(context(record, 3));

        Assert.assertTrue(result.isSuccess());
        Assert.assertFalse(result.isRetry());
        RetryRecord persisted = serializer.deserialize(result.getPayload());
        Assert.assertEquals(2, persisted.getState().getAttempts());
        Assert.assertEquals(RetryStatus.SUCCEEDED, persisted.getState().getStatus());
        Assert.assertEquals("payload-id-1", handler.payload);
        Assert.assertEquals(2, handler.context.getAttempt());
    }

    @Test
    public void testRetryableFailureReturnsRetryTaskResult() throws Exception {
        RecordingHandler handler = new RecordingHandler("payment", 2);
        RetryRecord record = RetryLeaseTestSupport.retryRecord(
                "payment", "id-1", RetryLeaseTestSupport.retryPolicy(2, 25L));
        TaskResult result = new RecoveryHandlerTaskHandlerAdapter(handler, serializer)
                .handle(context(record, 0));

        Assert.assertTrue(result.isRetry());
        Assert.assertEquals(Duration.ofMillis(25L), result.getRetryDelay());
        Assert.assertEquals("try 1 failed", result.getErrorMessage());
        RetryRecord persisted = serializer.deserialize(result.getPayload());
        Assert.assertEquals(1, persisted.getState().getAttempts());
        Assert.assertEquals(RetryStatus.WAITING_RETRY, persisted.getState().getStatus());
        Assert.assertNotNull(persisted.getState().getNextRunAt());
    }

    @Test
    public void testExhaustedFailureReturnsFailureTaskResult() throws Exception {
        RecordingHandler handler = new RecordingHandler("payment", 3);
        RetryRecord record = durableRecord(2, RetryLeaseTestSupport.retryPolicy(2, 25L));
        TaskResult result = new RecoveryHandlerTaskHandlerAdapter(handler, serializer)
                .handle(context(record, 0));

        Assert.assertTrue(result.isFailure());
        Assert.assertFalse(result.isRetry());
        Assert.assertEquals("try 1 failed", result.getErrorMessage());
        RetryRecord persisted = serializer.deserialize(result.getPayload());
        Assert.assertEquals(3, persisted.getState().getAttempts());
        Assert.assertEquals(RetryStatus.FAILED, persisted.getState().getStatus());
        Assert.assertNull(persisted.getState().getNextRunAt());
        Assert.assertNotNull(persisted.getState().getFailedAt());
    }

    @Test
    public void testSuccessSetsSucceededAt() throws Exception {
        RecordingHandler handler = new RecordingHandler("payment", 0);
        RetryRecord record = durableRecord(4, RetryLeaseTestSupport.retryPolicy(2, 10L));
        TaskResult result = new RecoveryHandlerTaskHandlerAdapter(handler, serializer)
                .handle(context(record, 0));

        RetryRecord persisted = serializer.deserialize(result.getPayload());
        Assert.assertNotNull(persisted.getState().getSucceededAt());
        Assert.assertNull(persisted.getState().getNextRunAt());
        Assert.assertEquals(5, handler.context.getAttempt());
    }

    @Test
    public void testCorruptPayloadFailsAsInfrastructureNotBusinessFailure() {
        RecordingHandler handler = new RecordingHandler("payment", 0);
        TaskContext context = new TestContext(
                "task-bad", "retry-recovery", "payment", "{not-json", 0);
        try {
            new RecoveryHandlerTaskHandlerAdapter(handler, serializer).handle(context);
            Assert.fail("Expected infrastructure deserialization failure");
        } catch (RetryInfrastructureException expected) {
            Assert.assertTrue(expected.getMessage().contains("Failed to deserialize"));
        }
        Assert.assertEquals(0, handler.calls.get());
    }

    @Test
    public void testInfrastructureSerializationFailureIsNotBusinessFailure() {
        RecordingHandler handler = new RecordingHandler("payment", 0);
        RetryRecord record = RetryLeaseTestSupport.retryRecord(
                "payment", "id-1", RetryLeaseTestSupport.retryPolicy(2, 25L));
        RecoveryHandlerTaskHandlerAdapter adapter = new RecoveryHandlerTaskHandlerAdapter(
                handler, new ThrowingSerializer());
        try {
            adapter.handle(context(record, 0));
            Assert.fail("Expected infrastructure serialization failure");
        } catch (RetryInfrastructureException expected) {
            Assert.assertTrue(expected.getMessage().contains(
                    "Business recovery succeeded but its retry result could not be serialized"));
        }
        Assert.assertEquals(1, handler.calls.get());
    }

    @Test
    public void testFailureResultSerializationFailureIsInfrastructureNotBusinessFailure() {
        RecordingHandler handler = new RecordingHandler("payment", 9);
        RetryRecord record = RetryLeaseTestSupport.retryRecord(
                "payment", "id-1", RetryLeaseTestSupport.retryPolicy(2, 25L));
        RecoveryHandlerTaskHandlerAdapter adapter = new RecoveryHandlerTaskHandlerAdapter(
                handler, new SelectiveThrowingSerializer());
        try {
            adapter.handle(context(record, 0));
            Assert.fail("Expected infrastructure serialization failure on failure result");
        } catch (RetryInfrastructureException expected) {
            Assert.assertTrue(expected.getMessage().contains(
                    "Business recovery failure result could not be serialized"));
        }
        Assert.assertEquals(1, handler.calls.get());
    }

    @Test
    public void testInterruptedExceptionIsInfrastructureAndRestoresInterruptFlag() {
        InterruptingHandler handler = new InterruptingHandler();
        RetryRecord record = RetryLeaseTestSupport.retryRecord(
                "payment", "interrupt", RetryLeaseTestSupport.retryPolicy(2, 25L));
        RecoveryHandlerTaskHandlerAdapter adapter =
                new RecoveryHandlerTaskHandlerAdapter(handler, serializer);
        try {
            adapter.handle(context(record, 0));
            Assert.fail("Expected interruption to be treated as infrastructure failure");
        } catch (RetryInfrastructureException expected) {
            Assert.assertTrue(expected.getCause() instanceof InterruptedException);
        } finally {
            // Clear the restored flag so it does not leak into later JUnit runs on this thread.
            Thread.interrupted();
        }
    }

    @Test
    public void testPreExistingInterruptFlagIsInfrastructureAndRemainsSet() {
        RecordingHandler handler = new RecordingHandler("payment", 0);
        RetryRecord record = RetryLeaseTestSupport.retryRecord(
                "payment", "pre-interrupt", RetryLeaseTestSupport.retryPolicy(2, 25L));
        RecoveryHandlerTaskHandlerAdapter adapter =
                new RecoveryHandlerTaskHandlerAdapter(handler, serializer);
        Thread.currentThread().interrupt();
        try {
            adapter.handle(context(record, 0));
            Assert.fail("Expected interrupt flag to be treated as infrastructure failure");
        } catch (RetryInfrastructureException expected) {
            Assert.assertTrue(expected.getCause() instanceof InterruptedException);
        } finally {
            Thread.interrupted();
        }
    }

    private RetryRecord durableRecord(int attempts, RetryPolicy policy) {
        RetryRecord record = RetryLeaseTestSupport.retryRecord("payment", "id-1", policy);
        record.getState().setAttempts(attempts);
        return record;
    }

    private static final class SelectiveThrowingSerializer implements RetryRecordSerializer {
        private int serializations;

        @Override
        public String serialize(RetryRecord record) {
            if (record.getState().getStatus() == RetryStatus.WAITING_RETRY
                    || record.getState().getStatus() == RetryStatus.FAILED) {
                throw new IllegalStateException("expected failure serialization failure");
            }
            return LeaseRetryRecordSerializer.INSTANCE.serialize(record);
        }

        @Override
        public RetryRecord deserialize(String data) {
            return LeaseRetryRecordSerializer.INSTANCE.deserialize(data);
        }
    }

    private static final class InterruptingHandler implements StringRecoveryHandler {
        @Override
        public String taskName() {
            return "payment";
        }

        @Override
        public void recover(String payload, RecoveryContext context) throws Exception {
            throw new InterruptedException("background shutdown");
        }
    }

    private static final class ThrowingSerializer implements RetryRecordSerializer {
        @Override
        public String serialize(RetryRecord record) {
            throw new IllegalStateException("expected serialization failure");
        }

        @Override
        public RetryRecord deserialize(String data) {
            return LeaseRetryRecordSerializer.INSTANCE.deserialize(data);
        }
    }

    private TaskContext context(RetryRecord record, int attemptCount) {
        return new TestContext(
                "task-1",
                "retry-recovery",
                record.getRequest().getTaskType(),
                serializer.serialize(record),
                attemptCount);
    }

    private static final class RecordingHandler implements StringRecoveryHandler {
        private final String name;
        private final int failures;
        private final AtomicInteger calls = new AtomicInteger();
        private String payload;
        private RecoveryContext context;

        private RecordingHandler(String name, int failures) {
            this.name = name;
            this.failures = failures;
        }

        @Override
        public String taskName() {
            return name;
        }

        @Override
        public void recover(String payload, RecoveryContext context) throws Exception {
            this.payload = payload;
            this.context = context;
            int attempt = calls.incrementAndGet();
            if (attempt <= failures) {
                throw new IllegalStateException("try " + attempt + " failed");
            }
        }
    }

    private static final class TestContext implements TaskContext {
        private final String taskId;
        private final String queue;
        private final String type;
        private final String payload;
        private final int attemptCount;

        private TestContext(
                String taskId,
                String queue,
                String type,
                String payload,
                int attemptCount) {
            this.taskId = taskId;
            this.queue = queue;
            this.type = type;
            this.payload = payload;
            this.attemptCount = attemptCount;
        }

        @Override
        public String getTaskId() {
            return taskId;
        }

        @Override
        public String getQueue() {
            return queue;
        }

        @Override
        public String getType() {
            return type;
        }

        @Override
        public String getPayload() {
            return payload;
        }

        @Override
        public int getAttemptCount() {
            return attemptCount;
        }

        @Override
        public Map<String, String> getAttributes() {
            return Collections.emptyMap();
        }

        @Override
        public Instant getCreatedAt() {
            return Instant.now();
        }

        @Override
        public Instant getVisibleAt() {
            return Instant.now();
        }
    }
}
