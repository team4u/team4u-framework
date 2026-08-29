package com.team4u.framework.lease.runtime;

import com.team4u.framework.base.util.DurationUtil;
import com.team4u.framework.lease.api.Submission;
import com.team4u.framework.lease.api.Task;
import com.team4u.framework.lease.api.TaskOperationResult;
import com.team4u.framework.lease.api.TaskPage;
import com.team4u.framework.lease.api.TaskPatch;
import com.team4u.framework.lease.api.TaskQuery;
import com.team4u.framework.lease.api.TaskResult;
import com.team4u.framework.lease.api.TaskQueue;
import com.team4u.framework.lease.api.TaskSnapshot;
import com.team4u.framework.lease.spi.AdminResult;
import com.team4u.framework.lease.spi.AdminCompletionCommand;
import com.team4u.framework.lease.spi.LeaseBackend;
import com.team4u.framework.lease.spi.LeaseCompletion;
import com.team4u.framework.lease.spi.RescheduleCommand;
import com.team4u.framework.lease.spi.RetryCommand;
import com.team4u.framework.lease.spi.SubmitCommand;
import com.team4u.framework.lease.spi.SubmitResult;
import com.team4u.framework.lease.spi.UpdateCommand;

import java.time.Duration;
import java.util.Optional;

public final class DefaultTaskQueue implements TaskQueue {

    private final LeaseBackend backend;
    private final String queueName;

    public DefaultTaskQueue(LeaseBackend backend, String queueName) {
        if (backend == null) {
            throw new IllegalArgumentException("backend must not be null");
        }
        if (queueName == null || queueName.trim().isEmpty()) {
            throw new IllegalArgumentException("queueName must not be blank");
        }
        this.backend = backend;
        this.queueName = queueName;
    }

    @Override
    public String name() {
        return queueName;
    }

    @Override
    public Submission submit(Task task) {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        SubmitResult result = backend.submit(SubmitCommand.of(
                queueName,
                task.getType(),
                task.getPayload(),
                task.getDeduplicationKey(),
                DurationUtil.requireExactMillis(task.getDelay(), "delay"),
                task.getPriority(),
                task.getAttributes()));
        return Submission.of(result.getTaskId(), result.isCreated(), result.getSnapshot());
    }

    @Override
    public Optional<TaskSnapshot> get(String taskId) {
        requireTaskId(taskId);
        return backend.get(queueName, taskId);
    }

    @Override
    public Optional<TaskSnapshot> get(String taskType, String dedupKey) {
        requireText(taskType, "type");
        requireText(dedupKey, "dedupKey");
        return backend.getByDeduplicationKey(queueName, taskType, dedupKey);
    }

    @Override
    public TaskPage list(TaskQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        return backend.list(queueName, query);
    }

    @Override
    public TaskOperationResult complete(String taskId, TaskResult result) {
        requireTaskId(taskId);
        requireTerminalResult(result);
        return operation(backend.complete(AdminCompletionCommand.of(
                queueName, taskId, toCompletion(result))));
    }

    @Override
    public TaskOperationResult cancel(String taskId, String reason) {
        requireTaskId(taskId);
        requireText(reason, "reason");
        return complete(taskId, TaskResult.cancel().withErrorMessage(reason));
    }
    @Override
    public TaskOperationResult reschedule(String taskId, Duration delay) {
        requireTaskId(taskId);
        return operation(backend.reschedule(RescheduleCommand.of(queueName, taskId,
                requireDelay(delay))));
    }

    @Override
    public TaskOperationResult retry(String taskId, Duration delay) {
        requireTaskId(taskId);
        return operation(backend.retry(RetryCommand.of(queueName, taskId, requireDelay(delay))));
    }

    @Override
    public TaskOperationResult update(TaskPatch patch) {
        return operation(backend.update(toUpdateCommand(patch, null)));
    }

    @Override
    public TaskOperationResult updateAndReschedule(TaskPatch patch, Duration delay) {
        return operation(backend.updateAndReschedule(toUpdateCommand(patch,
                Long.valueOf(requireDelay(delay)))));
    }

    @Override
    public TaskWorker.Builder worker() {
        return new TaskWorker.Builder(this, backend);
    }

    private static LeaseCompletion toCompletion(TaskResult result) {
        java.util.Map<String, String> attributes = result.hasAttributes()
                ? result.getAttributes() : null;
        if (result.isSuccess()) {
            return LeaseCompletion.succeeded(result.getPayload(), attributes);
        }
        if (result.isFailure()) {
            return LeaseCompletion.failed(result.getErrorMessage(), result.getPayload(), attributes);
        }
        return LeaseCompletion.cancelled(result.getErrorMessage(), result.getPayload(), attributes);
    }

    private static void requireTerminalResult(TaskResult result) {
        if (result == null) {
            throw new IllegalArgumentException("result must not be null");
        }
        if (result.isRetry()) {
            throw new IllegalArgumentException("retry result is not terminal; use retry instead");
        }
    }

    private UpdateCommand toUpdateCommand(TaskPatch patch, Long delayMillis) {
        if (patch == null) {
            throw new IllegalArgumentException("patch must not be null");
        }
        requireTaskId(patch.getTaskId());
        if (patch.getType() != null) {
            requireText(patch.getType(), "type");
        }
        return UpdateCommand.of(queueName,
                patch.getTaskId(),
                patch.getType(),
                patch.getPayload(),
                patch.getPriority(),
                patch.getAttributes(),
                patch.hasAttributes(),
                delayMillis);
    }
    private static long requireDelay(Duration delay) {
        return DurationUtil.requireExactMillis(delay, "delay");
    }

    private static void requireTaskId(String taskId) {
        requireText(taskId, "taskId");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static TaskOperationResult operation(AdminResult result) {
        if (result == AdminResult.APPLIED) {
            return TaskOperationResult.APPLIED;
        }
        if (result == AdminResult.TASK_NOT_FOUND) {
            return TaskOperationResult.TASK_NOT_FOUND;
        }
        if (result == AdminResult.TERMINAL) {
            return TaskOperationResult.TERMINAL;
        }
        if (result == AdminResult.ACTIVE_LEASE_PRESENT) {
            return TaskOperationResult.ACTIVE_LEASE_PRESENT;
        }
        throw new IllegalArgumentException("Unknown admin result: " + result);
    }
}
