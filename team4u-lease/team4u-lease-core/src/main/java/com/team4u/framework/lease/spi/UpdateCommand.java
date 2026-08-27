package com.team4u.framework.lease.spi;

import com.team4u.framework.lease.api.Task;

import java.util.Map;

public final class UpdateCommand {

    private final String queue;
    private final String taskId;
    private final String taskType;
    private final String payload;
    private final Integer priority;
    private final Map<String, String> attributes;
    private final boolean attributesPresent;
    private final Long delayMillis;

    private UpdateCommand(String queue, String taskId, String taskType, String payload,
                          Integer priority, Map<String, String> attributes, boolean attributesPresent,
                          Long delayMillis) {
        this.queue = LeaseValues.requireText(queue, "queue");
        this.taskId = LeaseValues.requireText(taskId, "taskId");
        this.taskType = taskType == null ? null : LeaseValues.requireText(taskType, "taskType");
        this.payload = payload;
        this.priority = priority == null ? null : Integer.valueOf(requirePriority(priority.intValue()));
        this.attributes = attributes == null ? LeaseValues.immutableAttributes(java.util.Collections.<String, String>emptyMap())
                : LeaseValues.immutableAttributes(attributes);
        this.attributesPresent = attributesPresent;
        this.delayMillis = delayMillis == null ? null
                : Long.valueOf(LeaseValues.requireMillis(delayMillis.longValue(), "delayMillis"));
    }

    public static UpdateCommand of(String queue, String taskId, String taskType, String payload,
                                   Integer priority, Map<String, String> attributes,
                                   boolean attributesPresent, Long delayMillis) {
        return new UpdateCommand(queue, taskId, taskType, payload, priority, attributes,
                attributesPresent, delayMillis);
    }

    private static int requirePriority(int priority) {
        if (priority < 0) {
            throw new IllegalArgumentException("priority must not be negative");
        }
        return priority;
    }

    public String getQueue() {
        return queue;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getTaskType() {
        return taskType;
    }

    public String getPayload() {
        return payload;
    }

    public Integer getPriority() {
        return priority;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public boolean hasAttributes() {
        return attributesPresent;
    }

    public Long getDelayMillis() {
        return delayMillis;
    }
}
