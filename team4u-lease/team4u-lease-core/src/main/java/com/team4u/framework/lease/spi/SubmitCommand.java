package com.team4u.framework.lease.spi;

import com.team4u.framework.lease.api.Task;

import java.util.Map;

public final class SubmitCommand {

    private final String queue;
    private final String taskType;
    private final String payload;
    private final String deduplicationKey;
    private final long delayMillis;
    private final int priority;
    private final Map<String, String> attributes;

    private SubmitCommand(String queue, String taskType, String payload, String deduplicationKey,
                          long delayMillis, int priority, Map<String, String> attributes) {
        this.queue = LeaseValues.requireText(queue, "queue");
        this.taskType = LeaseValues.requireText(taskType, "taskType");
        this.payload = payload;
        this.deduplicationKey = deduplicationKey == null ? null
                : LeaseValues.requireText(deduplicationKey, "deduplicationKey");
        this.delayMillis = LeaseValues.requireMillis(delayMillis, "delayMillis");
        if (priority < 0) {
            throw new IllegalArgumentException("priority must not be negative");
        }
        this.priority = priority;
        this.attributes = LeaseValues.immutableAttributes(attributes);
    }

    public static SubmitCommand of(String queue, String taskType, String payload, String deduplicationKey,
                                   long delayMillis, int priority, Map<String, String> attributes) {
        return new SubmitCommand(queue, taskType, payload, deduplicationKey, delayMillis,
                priority, attributes);
    }

    public String getQueue() {
        return queue;
    }

    public String getTaskType() {
        return taskType;
    }

    public String getPayload() {
        return payload;
    }

    public String getDeduplicationKey() {
        return deduplicationKey;
    }

    public long getDelayMillis() {
        return delayMillis;
    }

    public int getPriority() {
        return priority;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }
}
