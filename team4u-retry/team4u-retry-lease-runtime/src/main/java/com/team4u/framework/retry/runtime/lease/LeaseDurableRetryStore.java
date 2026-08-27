package com.team4u.framework.retry.runtime.lease;

import com.team4u.framework.lease.Leases;
import com.team4u.framework.lease.api.Submission;
import com.team4u.framework.lease.api.Task;
import com.team4u.framework.lease.api.TaskOperationResult;
import com.team4u.framework.lease.api.TaskPatch;
import com.team4u.framework.lease.api.TaskQueue;
import com.team4u.framework.lease.api.TaskResult;
import com.team4u.framework.lease.api.TaskSnapshot;
import com.team4u.framework.lease.api.TaskStatus;
import com.team4u.framework.lease.spi.LeaseBackend;
import com.team4u.framework.retry.managed.dispatch.DispatchResult;
import com.team4u.framework.retry.managed.dispatch.RetryDispatchCommand;
import com.team4u.framework.retry.managed.dispatch.RetryDispatcher;
import com.team4u.framework.retry.managed.model.RetryRequest;
import com.team4u.framework.retry.managed.model.RetryState;
import com.team4u.framework.retry.managed.model.RetryStatus;
import com.team4u.framework.retry.managed.store.RetryQueryService;
import com.team4u.framework.retry.managed.store.RetryStore;
import com.team4u.framework.retry.managed.store.record.CancelRecord;
import com.team4u.framework.retry.managed.store.record.FailureRecord;
import com.team4u.framework.retry.managed.store.record.ProcessingRecord;
import com.team4u.framework.retry.managed.store.record.RetryCreateRequest;
import com.team4u.framework.retry.managed.store.record.RetryRecord;
import com.team4u.framework.retry.managed.store.record.SubmitRecord;
import com.team4u.framework.retry.managed.store.record.SuccessRecord;
import com.team4u.framework.retry.managed.store.serialize.RetryRecordSerializer;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable retry store backed by a queue-scoped lease task API.
 */
public class LeaseDurableRetryStore implements RetryStore, RetryDispatcher, RetryQueryService {

    private static final Duration DEFAULT_FOREGROUND_RECOVERY_TIMEOUT = Duration.ofMinutes(5L);

    private final TaskQueue queue;
    private final RetryRecordSerializer serializer;
    private final long foregroundRecoveryTimeoutMillis;

    public LeaseDurableRetryStore(LeaseBackend backend) {
        this(Leases.queue(backend, RetryTaskQueues.DEFAULT_RECOVERY_QUEUE));
    }

    public LeaseDurableRetryStore(
            LeaseBackend backend, RetryRecordSerializer serializer) {
        this(Leases.queue(backend, RetryTaskQueues.DEFAULT_RECOVERY_QUEUE), serializer);
    }

    public LeaseDurableRetryStore(
            LeaseBackend backend, RetryRecordSerializer serializer,
            Duration foregroundRecoveryTimeout) {
        this(Leases.queue(backend, RetryTaskQueues.DEFAULT_RECOVERY_QUEUE),
                serializer, foregroundRecoveryTimeout);
    }

    public LeaseDurableRetryStore(TaskQueue queue) {
        this(queue, LeaseRetryRecordSerializer.INSTANCE);
    }

    public LeaseDurableRetryStore(TaskQueue queue, RetryRecordSerializer serializer) {
        this(queue, serializer, DEFAULT_FOREGROUND_RECOVERY_TIMEOUT);
    }

    public LeaseDurableRetryStore(
            TaskQueue queue, RetryRecordSerializer serializer,
            Duration foregroundRecoveryTimeout) {
        if (queue == null) {
            throw new IllegalArgumentException("TaskQueue must not be null");
        }
        if (serializer == null) {
            throw new IllegalArgumentException("RetryRecordSerializer must not be null");
        }
        this.foregroundRecoveryTimeoutMillis =
                requireExactPositiveMillis(foregroundRecoveryTimeout);
        this.queue = queue;
        this.serializer = serializer;
    }

    public long foregroundRecoveryTimeoutMillis() {
        return foregroundRecoveryTimeoutMillis;
    }

    public String queueName() {
        return queue.name();
    }

    @Override
    public SubmitRecord createIfAbsent(RetryCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("RetryCreateRequest must not be null");
        }
        validateCreateRequest(request);
        Instant foregroundDeadline = Instant.now().plusMillis(foregroundRecoveryTimeoutMillis);
        RetryRecord record = RetryRecord.builder()
                .request(request.getRequest())
                .state(request.getInitialState())
                .build();
        record.getState().setNextRunAt(foregroundDeadline);

        Submission submission = queue.submit(Task.of(request.getRequest().getTaskType(),
                        serializer.serialize(record))
                .deduplicationKey(request.getRequest().getIdempotencyKey())
                .delay(Duration.ofMillis(foregroundRecoveryTimeoutMillis)));
        TaskSnapshot snapshot = submission.getTask();
        RetryRecord resolved = submission.isCreated()
                ? record : serializer.deserialize(snapshot.getPayload());
        if (!submission.isCreated()) {
            validateExistingTaskId(resolved, request);
        }
        fillTaskId(resolved, submission.getTaskId());

        return SubmitRecord.builder()
                .created(submission.isCreated())
                .record(resolved)
                .build();
    }

    @Override
    public Optional<RetryRecord> get(String taskId) {
        return queue.get(taskId).map(this::deserialize);
    }

    @Override
    public Optional<RetryRecord> findByIdempotencyKey(String taskType, String idempotencyKey) {
        return queue.get(taskType, idempotencyKey).map(this::deserialize);
    }

    @Override
    public void markSucceeded(String taskId, SuccessRecord success) {
        Objects.requireNonNull(success, "SuccessRecord must not be null");
        RetryRecord record = required(taskId);
        record.getState().setStatus(RetryStatus.SUCCEEDED);
        record.getState().setNextRunAt(null);
        record.getState().setSucceededAt(success.getSucceededAt());
        if (success.getAttempts() != null) {
            record.getState().setAttempts(success.getAttempts());
        }
        TaskResult result = TaskResult.success(
                serializer.serialize(record), Collections.<String, String>emptyMap());
        assertApplied("completeSucceeded", taskId, queue.complete(taskId, result));
    }

    @Override
    public void markFailed(String taskId, FailureRecord failure) {
        Objects.requireNonNull(failure, "FailureRecord must not be null");
        RetryRecord record = required(taskId);
        record.getState().setStatus(RetryStatus.FAILED);
        record.getState().setNextRunAt(null);
        record.getState().setLastErrorCode(failure.getErrorCode());
        record.getState().setLastErrorMessage(failure.getErrorMessage());
        record.getState().setFailedAt(failure.getFailedAt());
        if (failure.getAttempts() != null) {
            record.getState().setAttempts(failure.getAttempts());
        }
        TaskResult result = TaskResult.failure(
                failure.getErrorMessage(), serializer.serialize(record),
                Collections.<String, String>emptyMap());
        assertApplied("completeFailed", taskId, queue.complete(taskId, result));
    }

    @Override
    public void markCancelled(String taskId, CancelRecord cancel) {
        Objects.requireNonNull(cancel, "CancelRecord must not be null");
        RetryRecord record = required(taskId);
        record.getState().setStatus(RetryStatus.CANCELLED);
        record.getState().setNextRunAt(null);
        record.getState().setLastErrorMessage(cancel.getReason());
        record.getState().setCancelledAt(cancel.getCancelledAt());
        assertApplied("completeCancelled", taskId, queue.complete(taskId,
                TaskResult.cancel(cancel.getReason(), serializer.serialize(record),
                        Collections.<String, String>emptyMap())));
    }

    @Override
    public void markProcessing(String taskId, ProcessingRecord record) {
        // Queue RUNNING state already represents processing, avoiding a write per attempt.
    }

    @Override
    public DispatchResult dispatch(RetryDispatchCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("RetryDispatchCommand must not be null");
        }
        RetryRecord record = required(command.getRecord().getTaskId());
        record.getState().setStatus(RetryStatus.WAITING_RETRY);
        record.getState().setAttempts(command.getTransition().getAttempts());
        record.getState().setNextRunAt(command.getTransition().getNextRunAt());
        record.getState().setLastErrorCode(command.getTransition().getLastErrorCode());
        record.getState().setLastErrorMessage(command.getTransition().getLastErrorMessage());
        record.getState().setBackendTaskId(record.getTaskId());

        assertApplied("updateAndReschedule", record.getTaskId(),
                queue.updateAndReschedule(TaskPatch.builder()
                                .taskId(record.getTaskId())
                                .payload(serializer.serialize(record))
                                .build(),
                        Duration.ofMillis(command.getDelayMillis())));

        return DispatchResult.builder()
                .taskId(record.getTaskId())
                .backendTaskId(record.getTaskId())
                .nextRunAt(command.getTransition().getNextRunAt())
                .build();
    }

    private RetryRecord required(String taskId) {
        return get(taskId).orElseThrow(
                () -> new IllegalStateException("Retry task not found: " + taskId));
    }

    private RetryRecord deserialize(TaskSnapshot snapshot) {
        RetryRecord record = serializer.deserialize(snapshot.getPayload());
        fillTaskId(record, snapshot.getTaskId());
        if (record.getState() != null) {
            record.getState().setBackendTaskId(snapshot.getTaskId());
            if (snapshot.getStatus() == TaskStatus.RUNNING) {
                record.getState().setStatus(RetryStatus.PROCESSING);
                record.getState().setNextRunAt(null);
            }
        }
        return record;
    }

    private void fillTaskId(RetryRecord record, String taskId) {
        record.setTaskId(taskId);
        if (record.getRequest() != null) {
            record.getRequest().setTaskId(taskId);
        }
    }

    private void assertApplied(String operation, String taskId, TaskOperationResult result) {
        if (result != TaskOperationResult.APPLIED) {
            throw new TaskQueueOperationException(operation, taskId, result);
        }
    }

    private static void validateCreateRequest(RetryCreateRequest request) {
        RetryRequest retryRequest = request.getRequest();
        if (retryRequest == null) {
            throw new IllegalArgumentException("RetryCreateRequest.request must not be null");
        }
        requireText(retryRequest.getTaskType(), "RetryCreateRequest.request.taskType");
        if (isBlank(retryRequest.getIdempotencyKey())) {
            throw new IllegalArgumentException(
                    "RetryCreateRequest.request.idempotencyKey must not be blank");
        }
        if (retryRequest.getRecovery() == null
                || isBlank(retryRequest.getRecovery().getTaskType())) {
            throw new IllegalArgumentException(
                    "RetryCreateRequest.request.recovery.taskType must not be blank");
        }
        if (retryRequest.getPolicy() == null) {
            throw new IllegalArgumentException(
                    "RetryCreateRequest.request.policy must not be null");
        }
        if (retryRequest.getCreatedAt() == null) {
            throw new IllegalArgumentException(
                    "RetryCreateRequest.request.createdAt must not be null");
        }
        RetryState initialState = request.getInitialState();
        if (initialState == null) {
            throw new IllegalArgumentException(
                    "RetryCreateRequest.initialState must not be null");
        }
        if (initialState.getStatus() == null) {
            throw new IllegalArgumentException(
                    "RetryCreateRequest.initialState.status must not be null");
        }
        if (initialState.getAttempts() < 0) {
            throw new IllegalArgumentException(
                    "RetryCreateRequest.initialState.attempts must not be negative");
        }
    }

    private static void validateExistingTaskId(RetryRecord resolved, RetryCreateRequest request) {
        String requestTaskId = request.getRequest().getTaskId();
        if (requestTaskId != null && !requestTaskId.equals(resolved.getRequest().getTaskId())) {
            throw new IllegalArgumentException(
                    "Existing retry task id does not match request.taskId");
        }
    }

    private static long requireExactPositiveMillis(Duration duration) {
        if (duration == null) {
            throw new IllegalArgumentException(
                    "foregroundRecoveryTimeout must not be null");
        }
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(
                    "foregroundRecoveryTimeout must contain positive milliseconds");
        }
        if (duration.getNano() % 1_000_000L != 0L) {
            throw new IllegalArgumentException(
                    "foregroundRecoveryTimeout must be exact in milliseconds");
        }
        try {
            long millis = duration.toMillis();
            if (millis <= 0L) {
                throw new ArithmeticException("non-positive");
            }
            return millis;
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(
                    "foregroundRecoveryTimeout must fit in positive milliseconds", ex);
        }
    }

    private static void requireText(String value, String name) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
