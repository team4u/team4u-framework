package com.team4u.framework.lease.spi;

import com.team4u.framework.lease.api.Task;

public final class RetryCommand {

    private final String queue;
    private final String taskId;
    private final long delayMillis;

    private RetryCommand(String queue, String taskId, long delayMillis) {
        this.queue = LeaseValues.requireText(queue, "queue");
        this.taskId = LeaseValues.requireText(taskId, "taskId");
        this.delayMillis = LeaseValues.requireMillis(delayMillis, "delayMillis");
    }

    public static RetryCommand of(String queue, String taskId, long delayMillis) {
        return new RetryCommand(queue, taskId, delayMillis);
    }

    public String getQueue() {
        return queue;
    }

    public String getTaskId() {
        return taskId;
    }

    public long getDelayMillis() {
        return delayMillis;
    }
}
