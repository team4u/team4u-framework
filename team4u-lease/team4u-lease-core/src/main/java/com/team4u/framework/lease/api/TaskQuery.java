package com.team4u.framework.lease.api;

public final class TaskQuery {

    private final String type;
    private final TaskStatus status;
    private final String workerId;
    private final int page;
    private final int pageSize;

    private TaskQuery(Builder builder) {
        this.type = optionalText(builder.type, "type");
        this.status = builder.status;
        this.workerId = optionalText(builder.workerId, "workerId");
        if (builder.page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (builder.pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
        this.page = builder.page;
        this.pageSize = builder.pageSize;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getType() {
        return type;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public String getWorkerId() {
        return workerId;
    }

    public int getPage() {
        return page;
    }

    public int getPageSize() {
        return pageSize;
    }

    private static String optionalText(String value, String name) {
        if (value == null) {
            return null;
        }
        return Task.requireText(value, name);
    }
    public static final class Builder {
        private String type;
        private TaskStatus status;
        private String workerId;
        private int page;
        private int pageSize = 50;

        private Builder() {
        }

        public Builder type(String type) {
            this.type = optionalText(type, "type");
            return this;
        }

        public Builder status(TaskStatus status) {
            this.status = status;
            return this;
        }

        public Builder workerId(String workerId) {
            this.workerId = optionalText(workerId, "workerId");
            return this;
        }

        public Builder page(int page) {
            this.page = page;
            return this;
        }

        public Builder pageSize(int pageSize) {
            this.pageSize = pageSize;
            return this;
        }

        public TaskQuery build() {
            return new TaskQuery(this);
        }
    }
}
