package com.team4u.framework.lease.spi;

import com.team4u.framework.lease.api.TaskSnapshot;

public final class SubmitResult {

    private final String taskId;
    private final boolean created;
    private final TaskSnapshot snapshot;

    private SubmitResult(String taskId, boolean created, TaskSnapshot snapshot) {
        if (taskId == null || taskId.trim().isEmpty()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        this.taskId = taskId;
        this.created = created;
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        if (!taskId.equals(snapshot.getTaskId())) {
            throw new IllegalArgumentException("taskId must match snapshot taskId");
        }
        this.snapshot = snapshot;
    }

    public static SubmitResult of(String taskId, boolean created, TaskSnapshot snapshot) {
        return new SubmitResult(taskId, created, snapshot);
    }

    public String getTaskId() {
        return taskId;
    }

    public boolean isCreated() {
        return created;
    }

    public TaskSnapshot getSnapshot() {
        return snapshot;
    }
}
