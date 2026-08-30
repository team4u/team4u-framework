package com.team4u.framework.lease.api;

public final class Submission {

    private final String taskId;
    private final boolean created;
    private final TaskSnapshot task;

    private Submission(String taskId, boolean created, TaskSnapshot task) {
        this.taskId = Task.requireText(taskId, "taskId");
        this.created = created;
        this.task = task;
        if (task == null) {
            throw new IllegalArgumentException("task snapshot must not be null");
        }
        if (!taskId.equals(task.getTaskId())) {
            throw new IllegalArgumentException("taskId must match task snapshot taskId");
        }
    }

    public static Submission of(String taskId, boolean created, TaskSnapshot task) {
        return new Submission(taskId, created, task);
    }

    public String getTaskId() {
        return taskId;
    }

    public boolean isCreated() {
        return created;
    }

    public TaskSnapshot getTask() {
        return task;
    }
}
