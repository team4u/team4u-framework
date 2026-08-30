package com.team4u.framework.lease.api;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TaskSnapshot {

    private final String taskId;
    private final String queue;
    private final String type;
    private final String payload;
    private final String dedupKey;
    private final TaskStatus status;
    private final String workerId;
    private final int priority;
    private final int attemptCount;
    private final Instant createdAt;
    private final Instant visibleAt;
    private final Instant leaseExpiresAt;
    private final String errorMessage;
    private final Map<String, String> attributes;

    private TaskSnapshot(Builder builder) {
        this.taskId = Task.requireText(builder.taskId, "taskId");
        this.queue = Task.requireText(builder.queue, "queue");
        this.type = Task.requireText(builder.type, "type");
        this.payload = builder.payload;
        this.dedupKey = builder.dedupKey;
        this.status = requireStatus(builder.status);
        this.workerId = requireWorkerId(builder.workerId, this.status);
        this.priority = Task.requirePriority(builder.priority);
        this.attemptCount = Task.requireNonNegative(builder.attemptCount);
        this.createdAt = requireTime(builder.createdAt, "createdAt");
        this.visibleAt = requireTime(builder.visibleAt, "visibleAt");
        this.leaseExpiresAt = requireLeaseExpiresAt(builder.leaseExpiresAt, this.status,
                this.workerId);
        this.errorMessage = builder.errorMessage;
        this.attributes = Task.immutableAttributes(builder.attributes);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getTaskId() {
        return taskId;
    }

    public String getQueue() {
        return queue;
    }

    public String getType() {
        return type;
    }

    public String getPayload() {
        return payload;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public String getWorkerId() {
        return workerId;
    }

    public int getPriority() {
        return priority;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getVisibleAt() {
        return visibleAt;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    private static TaskStatus requireStatus(TaskStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        return status;
    }

    private static String requireWorkerId(String workerId, TaskStatus status) {
        if (status == TaskStatus.RUNNING) {
            return Task.requireText(workerId, "workerId");
        }
        if (workerId != null) {
            throw new IllegalArgumentException("workerId is only valid for RUNNING tasks");
        }
        return null;
    }

    private static Instant requireLeaseExpiresAt(Instant leaseExpiresAt, TaskStatus status,
                                                  String workerId) {
        if (status == TaskStatus.RUNNING) {
            if (leaseExpiresAt == null) {
                throw new IllegalArgumentException("leaseExpiresAt must not be null");
            }
            return leaseExpiresAt;
        }
        if (leaseExpiresAt != null) {
            throw new IllegalArgumentException("leaseExpiresAt is only valid for RUNNING tasks");
        }
        return null;
    }

    private static Instant requireTime(Instant instant, String name) {
        if (instant == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return instant;
    }

    public static final class Builder {
        private String taskId;
        private String queue;
        private String type;
        private String payload;
        private String dedupKey;
        private TaskStatus status;
        private String workerId;
        private int priority;
        private int attemptCount;
        private Instant createdAt;
        private Instant visibleAt;
        private Instant leaseExpiresAt;
        private String errorMessage;
        private Map<String, String> attributes = Collections.emptyMap();

        private Builder() {
        }

        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder queue(String queue) {
            this.queue = queue;
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

        public Builder dedupKey(String dedupKey) {
            this.dedupKey = dedupKey;
            return this;
        }

        public Builder status(TaskStatus status) {
            this.status = status;
            return this;
        }

        public Builder workerId(String workerId) {
            this.workerId = workerId;
            return this;
        }

        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        public Builder attemptCount(int attemptCount) {
            this.attemptCount = attemptCount;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder visibleAt(Instant visibleAt) {
            this.visibleAt = visibleAt;
            return this;
        }

        public Builder leaseExpiresAt(Instant leaseExpiresAt) {
            this.leaseExpiresAt = leaseExpiresAt;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public Builder attributes(Map<String, String> attributes) {
            this.attributes = Task.immutableAttributes(attributes);
            return this;
        }

        public Builder attribute(String key, String value) {
            Map<String, String> next = new LinkedHashMap<String, String>(attributes);
            next.put(key, value);
            this.attributes = Task.immutableAttributes(next);
            return this;
        }

        public TaskSnapshot build() {
            return new TaskSnapshot(this);
        }
    }
}
