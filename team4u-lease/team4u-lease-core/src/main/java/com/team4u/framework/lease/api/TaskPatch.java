package com.team4u.framework.lease.api;

import java.util.Map;

public final class TaskPatch {

    private final String taskId;
    private final String type;
    private final String payload;
    private final Integer priority;
    private final Map<String, String> attributes;
    private final boolean attributesPresent;

    private TaskPatch(Builder builder) {
        this.taskId = builder.taskId;
        this.type = builder.type;
        this.payload = builder.payload;
        this.priority = builder.priority;
        this.attributes = builder.attributes;
        this.attributesPresent = builder.attributesPresent;
        if (attributesPresent && attributes == null) {
            throw new IllegalArgumentException("attributes must not be null");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getTaskId() {
        return taskId;
    }

    public String getType() {
        return type;
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

    public static final class Builder {
        private String taskId;
        private String type;
        private String payload;
        private Integer priority;
        private Map<String, String> attributes;
        private boolean attributesPresent;

        private Builder() {
        }

        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder payload(String payload) {
            this.payload = payload;
            return this;
        }

        public Builder priority(Integer priority) {
            if (priority != null && priority.intValue() < 0) {
                throw new IllegalArgumentException("priority must not be negative");
            }
            this.priority = priority;
            return this;
        }

        public Builder attributes(Map<String, String> attributes) {
            this.attributes = Task.immutableAttributes(attributes);
            this.attributesPresent = true;
            return this;
        }

        public TaskPatch build() {
            return new TaskPatch(this);
        }
    }
}
