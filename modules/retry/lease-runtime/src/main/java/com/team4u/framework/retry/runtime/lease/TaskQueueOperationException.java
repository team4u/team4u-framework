package com.team4u.framework.retry.runtime.lease;

import com.team4u.framework.lease.api.TaskOperationResult;
import lombok.Getter;

/**
 * Thrown when a task queue management operation is not applied by the backend.
 */
@Getter
public class TaskQueueOperationException extends IllegalStateException {

    private final String operation;
    private final String taskId;
    private final TaskOperationResult result;
    private final boolean retriable;

    public TaskQueueOperationException(String operation, String taskId, TaskOperationResult result) {
        super("Task queue " + operation + " was not applied for taskId=" + taskId
                + ", result=" + result);
        this.operation = operation;
        this.taskId = taskId;
        this.result = result;
        this.retriable = result == TaskOperationResult.ACTIVE_LEASE_PRESENT;
    }
}
