package com.team4u.framework.lease.spi;

import com.team4u.framework.lease.api.Task;

public final class RescheduleCommand {

    private final String queue;
    private final String taskId;
    private final long delayMillis;

    private RescheduleCommand(String queue, String taskId, long delayMillis) {
        this.queue = LeaseValues.requireText(queue, "queue");
        this.taskId = LeaseValues.requireText(taskId, "taskId");
        this.delayMillis = LeaseValues.requireMillis(delayMillis, "delayMillis");
    }

    public static RescheduleCommand of(String queue, String taskId, long delayMillis) {
        return new RescheduleCommand(queue, taskId, delayMillis);
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
