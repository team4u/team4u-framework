package com.team4u.framework.lease.spi;

import com.team4u.framework.lease.api.Task;

public final class TaskSubscription {

    private final String queue;
    private final java.util.Set<String> taskTypes;

    private TaskSubscription(String queue, java.util.Set<String> taskTypes) {
        this.queue = LeaseValues.requireText(queue, "queue");
        if (taskTypes == null || taskTypes.isEmpty()) {
            throw new IllegalArgumentException("taskTypes must not be empty");
        }
        java.util.Set<String> copied = new java.util.LinkedHashSet<String>();
        for (String taskType : taskTypes) {
            String normalized = LeaseValues.requireText(taskType, "taskTypes");
            if (normalized.indexOf('*') >= 0 || normalized.indexOf('>') >= 0) {
                throw new IllegalArgumentException("taskTypes does not support wildcard expressions");
            }
            copied.add(normalized);
        }
        this.taskTypes = java.util.Collections.unmodifiableSet(copied);
    }

    public static TaskSubscription of(String queue, java.util.Set<String> taskTypes) {
        return new TaskSubscription(queue, taskTypes);
    }

    public String getQueue() {
        return queue;
    }

    public java.util.Set<String> getTaskTypes() {
        return taskTypes;
    }
}
