package com.team4u.framework.lease;

import com.team4u.framework.lease.api.Submission;
import com.team4u.framework.lease.api.Task;
import com.team4u.framework.lease.api.TaskOperationResult;
import com.team4u.framework.lease.api.TaskPage;
import com.team4u.framework.lease.api.TaskPatch;
import com.team4u.framework.lease.api.TaskQuery;
import com.team4u.framework.lease.api.TaskQueue;
import com.team4u.framework.lease.api.TaskResult;
import com.team4u.framework.lease.api.TaskSnapshot;
import com.team4u.framework.lease.api.TaskStatus;
import com.team4u.framework.lease.spi.AcquireCommand;
import com.team4u.framework.lease.spi.AdminResult;
import com.team4u.framework.lease.spi.AdminCompletionCommand;
import com.team4u.framework.lease.spi.LeaseBackend;
import com.team4u.framework.lease.spi.LeaseCompletion;
import com.team4u.framework.lease.spi.LeaseGrant;
import com.team4u.framework.lease.spi.LeaseHandle;
import com.team4u.framework.lease.spi.LeaseRetry;
import com.team4u.framework.lease.spi.RuntimeResult;
import com.team4u.framework.lease.spi.RescheduleCommand;
import com.team4u.framework.lease.spi.RetryCommand;
import com.team4u.framework.lease.spi.SubmitCommand;
import com.team4u.framework.lease.spi.SubmitResult;
import com.team4u.framework.lease.spi.TaskSubscription;
import com.team4u.framework.lease.spi.UpdateCommand;
import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class TaskQueueTest {

    @Test
    public void testInjectsQueueAndConvertsDurations() {
        RecordingBackend backend = new RecordingBackend();
        TaskQueue queue = Leases.queue(backend, "orders");

        Assert.assertEquals("orders", queue.name());

        Map<String, String> attributes = new LinkedHashMap<String, String>();
        attributes.put("traceId", "trace-1");
        Task task = Task.of("email.send", "{}")
                .deduplicationKey("order-1")
                .delay(Duration.ofMillis(250))
                .priority(7)
                .attributes(attributes);

        Submission submission = queue.submit(task);

        Assert.assertEquals("task-1", submission.getTaskId());
        Assert.assertTrue(submission.isCreated());
        Assert.assertSame(backend.snapshot(), submission.getTask());

        SubmitCommand command = backend.lastSubmitCommand;
        Assert.assertEquals("orders", command.getQueue());
        Assert.assertEquals("email.send", command.getTaskType());
        Assert.assertEquals("order-1", command.getDeduplicationKey());
        Assert.assertEquals(250L, command.getDelayMillis());
        Assert.assertEquals(7, command.getPriority());
        Assert.assertEquals(Collections.singletonMap("traceId", "trace-1"), command.getAttributes());
    }

    @Test
    public void testQueueScopedQueriesAndDedupLookup() {
        RecordingBackend backend = new RecordingBackend();
        TaskQueue queue = Leases.queue(backend, "orders");

        queue.get("task-1");
        Assert.assertEquals("orders", backend.lastGetQueue);
        Assert.assertEquals("task-1", backend.lastTaskId);

        queue.get("email.send", "order-1");
        Assert.assertEquals("orders", backend.lastDedupQueue);
        Assert.assertEquals("email.send", backend.lastDedupType);
        Assert.assertEquals("order-1", backend.lastDedupKey);

        TaskQuery query = TaskQuery.builder()
                .type("email.send")
                .status(TaskStatus.PENDING)
                .workerId("worker-1")
                .page(2)
                .pageSize(25)
                .build();
        TaskPage page = queue.list(query);
        Assert.assertSame(page, backend.page);
        Assert.assertEquals("orders", backend.lastListQueue);
        Assert.assertSame(query, backend.lastListQuery);
    }

    @Test
    public void testOperationsInjectQueueAndConvertDurations() {
        RecordingBackend backend = new RecordingBackend();
        TaskQueue queue = Leases.queue(backend, "orders");

        Assert.assertEquals(TaskOperationResult.APPLIED,
                queue.complete("task-1", TaskResult.success().withPayload("done")));
        AdminCompletionCommand completion = backend.lastCompletionCommand;
        Assert.assertEquals("orders", completion.getQueue());
        Assert.assertEquals("task-1", completion.getTaskId());
        Assert.assertEquals(TaskStatus.SUCCEEDED, completion.getCompletion().getStatus());
        Assert.assertEquals("done", completion.getCompletion().getPayload());
        Assert.assertFalse(completion.getCompletion().hasAttributes());

        queue.cancel("task-1", "user requested");
        Assert.assertEquals(TaskStatus.CANCELLED, backend.lastCompletionCommand.getCompletion().getStatus());
        Assert.assertEquals("user requested",
                backend.lastCompletionCommand.getCompletion().getErrorMessage());

        Assert.assertEquals(TaskOperationResult.APPLIED, queue.reschedule("task-1", Duration.ofSeconds(2)));
        Assert.assertEquals(2000L, backend.lastRescheduleCommand.getDelayMillis());

        Assert.assertEquals(TaskOperationResult.APPLIED, queue.retry("task-1", Duration.ofSeconds(3)));
        Assert.assertEquals(3000L, backend.lastRetryCommand.getDelayMillis());

        TaskPatch patch = TaskPatch.builder().taskId("task-1").payload("payload-v2").build();
        Assert.assertEquals(TaskOperationResult.APPLIED, queue.update(patch));
        Assert.assertEquals("orders", backend.lastUpdateCommand.getQueue());
        Assert.assertEquals("task-1", backend.lastUpdateCommand.getTaskId());
        Assert.assertEquals("payload-v2", backend.lastUpdateCommand.getPayload());
        Assert.assertFalse(backend.lastUpdateCommand.hasAttributes());

        Map<String, String> clear = Collections.emptyMap();
        TaskPatch clearPatch = TaskPatch.builder().taskId("task-1").attributes(clear).build();
        queue.update(clearPatch);
        Assert.assertTrue(backend.lastUpdateCommand.hasAttributes());
        Assert.assertTrue(backend.lastUpdateCommand.getAttributes().isEmpty());

        Assert.assertEquals(TaskOperationResult.APPLIED,
                queue.updateAndReschedule(patch, Duration.ofSeconds(4)));
        Assert.assertEquals(Long.valueOf(4000L), backend.lastUpdateCommand.getDelayMillis());
    }

    @Test
    public void testMapsAdminResults() {
        RecordingBackend backend = new RecordingBackend();
        TaskQueue queue = Leases.queue(backend, "orders");

        backend.completionResult = AdminResult.TASK_NOT_FOUND;
        Assert.assertEquals(TaskOperationResult.TASK_NOT_FOUND,
                queue.complete("task-1", TaskResult.failure("missing")));

        backend.completionResult = AdminResult.TERMINAL;
        Assert.assertEquals(TaskOperationResult.TERMINAL,
                queue.complete("task-1", TaskResult.failure("terminal")));

        backend.completionResult = AdminResult.ACTIVE_LEASE_PRESENT;
        Assert.assertEquals(TaskOperationResult.ACTIVE_LEASE_PRESENT,
                queue.complete("task-1", TaskResult.failure("lease")));
    }

    @Test
    public void testCompleteMapsFailureAttributesAndRejectsRetry() {
        RecordingBackend backend = new RecordingBackend();
        TaskQueue queue = Leases.queue(backend, "orders");

        Map<String, String> attributes = new LinkedHashMap<String, String>();
        attributes.put("traceId", "trace-1");
        Assert.assertEquals(TaskOperationResult.APPLIED, queue.complete("task-1",
                TaskResult.failure("bad input", "failed-payload", attributes)));
        LeaseCompletion failure = backend.lastCompletionCommand.getCompletion();
        Assert.assertEquals(TaskStatus.FAILED, failure.getStatus());
        Assert.assertEquals("bad input", failure.getErrorMessage());
        Assert.assertEquals("failed-payload", failure.getPayload());
        Assert.assertTrue(failure.hasAttributes());
        Assert.assertEquals(attributes, failure.getAttributes());

        Assert.assertEquals(TaskOperationResult.APPLIED, queue.complete("task-1",
                TaskResult.failure("clear attrs", "payload", Collections.<String, String>emptyMap())));
        Assert.assertTrue(backend.lastCompletionCommand.getCompletion().hasAttributes());
        Assert.assertTrue(backend.lastCompletionCommand.getCompletion().getAttributes().isEmpty());

        Assert.assertEquals(TaskOperationResult.APPLIED, queue.complete("task-1",
                TaskResult.cancel("user stopped", "cancelled-payload",
                        Collections.<String, String>emptyMap())));
        LeaseCompletion cancelled = backend.lastCompletionCommand.getCompletion();
        Assert.assertEquals(TaskStatus.CANCELLED, cancelled.getStatus());
        Assert.assertEquals("user stopped", cancelled.getErrorMessage());
        Assert.assertEquals("cancelled-payload", cancelled.getPayload());
        Assert.assertTrue(cancelled.hasAttributes());
        Assert.assertTrue(cancelled.getAttributes().isEmpty());

        try {
            queue.complete("task-1", TaskResult.retryAfter(Duration.ZERO));
            Assert.fail("expected retry completion to be rejected");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("retry"));
        }

        try {
            queue.complete("task-1", null);
            Assert.fail("expected null result to be rejected");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("result"));
        }
    }

    @Test
    public void testRejectsInvalidConstruction() {
        assertQueueRejected(null);
        assertQueueRejected("");
        assertQueueRejected(" ");
    }

    private void assertQueueRejected(String queueName) {
        try {
            Leases.queue(new RecordingBackend(), queueName);
            Assert.fail("expected invalid queue to be rejected");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("queueName"));
        }
    }

    private static class RecordingBackend implements LeaseBackend {
        private SubmitCommand lastSubmitCommand;
        private String lastGetQueue;
        private String lastTaskId;
        private String lastDedupQueue;
        private String lastDedupType;
        private String lastDedupKey;
        private String lastListQueue;
        private TaskQuery lastListQuery;
        private TaskPage page = TaskPage.of(Collections.<TaskSnapshot>emptyList(), 0, 1, 0);
        private AdminCompletionCommand lastCompletionCommand;
        private RescheduleCommand lastRescheduleCommand;
        private RetryCommand lastRetryCommand;
        private UpdateCommand lastUpdateCommand;
        private AdminResult completionResult = AdminResult.APPLIED;
        private TaskSnapshot snapshot;

        @Override
        public SubmitResult submit(SubmitCommand command) {
            lastSubmitCommand = command;
            return SubmitResult.of("task-1", true, snapshot());
        }

        @Override
        public Optional<TaskSnapshot> get(String queue, String taskId) {
            lastGetQueue = queue;
            lastTaskId = taskId;
            return Optional.of(snapshot());
        }

        @Override
        public Optional<TaskSnapshot> getByDeduplicationKey(String queue, String taskType, String key) {
            lastDedupQueue = queue;
            lastDedupType = taskType;
            lastDedupKey = key;
            return Optional.of(snapshot());
        }

        @Override
        public LeaseGrant acquire(AcquireCommand command) {
            throw new UnsupportedOperationException("not expected");
        }

        @Override
        public RuntimeResult heartbeat(LeaseHandle handle, long extendMillis) {
            throw new UnsupportedOperationException("not expected");
        }

        @Override
        public RuntimeResult close(LeaseHandle handle, LeaseCompletion completion) {
            throw new UnsupportedOperationException("not expected");
        }

        @Override
        public RuntimeResult release(LeaseHandle handle, LeaseRetry retry) {
            throw new UnsupportedOperationException("not expected");
        }

        @Override
        public TaskPage list(String queue, TaskQuery query) {
            lastListQueue = queue;
            lastListQuery = query;
            return page;
        }

        @Override
        public AdminResult complete(AdminCompletionCommand command) {
            lastCompletionCommand = command;
            return completionResult;
        }

        @Override
        public AdminResult reschedule(RescheduleCommand command) {
            lastRescheduleCommand = command;
            return AdminResult.APPLIED;
        }

        @Override
        public AdminResult retry(RetryCommand command) {
            lastRetryCommand = command;
            return AdminResult.APPLIED;
        }

        @Override
        public AdminResult update(UpdateCommand command) {
            lastUpdateCommand = command;
            return AdminResult.APPLIED;
        }

        @Override
        public AdminResult updateAndReschedule(UpdateCommand command) {
            lastUpdateCommand = command;
            return AdminResult.APPLIED;
        }

        private TaskSnapshot snapshot() {
            if (snapshot == null) {
                snapshot = TaskSnapshot.builder()
                        .taskId("task-1")
                        .queue("orders")
                        .type("email.send")
                        .payload("{}")
                        .dedupKey("order-1")
                        .status(TaskStatus.PENDING)
                        .priority(0)
                        .attemptCount(0)
                        .createdAt(Instant.EPOCH)
                        .visibleAt(Instant.EPOCH)
                        .build();
            }
            return snapshot;
        }
    }
}
