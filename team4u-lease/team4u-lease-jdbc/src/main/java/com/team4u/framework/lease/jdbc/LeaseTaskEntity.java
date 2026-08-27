package com.team4u.framework.lease.jdbc;

import com.team4u.framework.lease.api.TaskStatus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Row representation of lease_task. Times and version are stored as epoch milliseconds.
 */
public final class LeaseTaskEntity {

    private final String taskId;
    private final String queueName;
    private final String taskType;
    private final String payload;
    private final String deduplicationKey;
    private final TaskStatus status;
    private final int priority;
    private final int attemptCount;
    private final String workerId;
    private final String leaseToken;
    private final Long leaseExpiresAt;
    private final long visibleAt;
    private final long createdAt;
    private final long updatedAt;
    private final long version;
    private final String errorMessage;
    private final Map<String, String> attributes;

    private LeaseTaskEntity(Builder builder) {
        this.taskId = builder.taskId;
        this.queueName = builder.queueName;
        this.taskType = builder.taskType;
        this.payload = builder.payload;
        this.deduplicationKey = builder.deduplicationKey;
        this.status = builder.status;
        this.priority = builder.priority;
        this.attemptCount = builder.attemptCount;
        this.workerId = builder.workerId;
        this.leaseToken = builder.leaseToken;
        this.leaseExpiresAt = builder.leaseExpiresAt;
        this.visibleAt = builder.visibleAt;
        this.createdAt = builder.createdAt;
        this.updatedAt = builder.updatedAt;
        this.version = builder.version;
        this.errorMessage = builder.errorMessage;
        this.attributes = immutableAttributes(builder.attributes);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getTaskId() {
        return taskId;
    }

    public String getQueueName() {
        return queueName;
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

    public TaskStatus getStatus() {
        return status;
    }

    public int getPriority() {
        return priority;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public String getWorkerId() {
        return workerId;
    }

    public String getLeaseToken() {
        return leaseToken;
    }

    public Long getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public long getVisibleAt() {
        return visibleAt;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    private static Map<String, String> immutableAttributes(Map<String, String> values) {
        if (values == null) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<String, String>(values));
    }

    public static final class Builder {
        private String taskId;
        private String queueName;
        private String taskType;
        private String payload;
        private String deduplicationKey;
        private TaskStatus status;
        private int priority;
        private int attemptCount;
        private String workerId;
        private String leaseToken;
        private Long leaseExpiresAt;
        private long visibleAt;
        private long createdAt;
        private long updatedAt;
        private long version;
        private String errorMessage;
        private Map<String, String> attributes;

        private Builder() {
        }

        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder queueName(String queueName) {
            this.queueName = queueName;
            return this;
        }

        public Builder taskType(String taskType) {
            this.taskType = taskType;
            return this;
        }

        public Builder payload(String payload) {
            this.payload = payload;
            return this;
        }

        public Builder deduplicationKey(String deduplicationKey) {
            this.deduplicationKey = deduplicationKey;
            return this;
        }

        public Builder status(TaskStatus status) {
            this.status = status;
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

        public Builder workerId(String workerId) {
            this.workerId = workerId;
            return this;
        }

        public Builder leaseToken(String leaseToken) {
            this.leaseToken = leaseToken;
            return this;
        }

        public Builder leaseExpiresAt(Long leaseExpiresAt) {
            this.leaseExpiresAt = leaseExpiresAt;
            return this;
        }

        public Builder visibleAt(long visibleAt) {
            this.visibleAt = visibleAt;
            return this;
        }

        public Builder createdAt(long createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder updatedAt(long updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        public Builder version(long version) {
            this.version = version;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public Builder attributes(Map<String, String> attributes) {
            this.attributes = attributes;
            return this;
        }

        public LeaseTaskEntity build() {
            return new LeaseTaskEntity(this);
        }
    }
}
