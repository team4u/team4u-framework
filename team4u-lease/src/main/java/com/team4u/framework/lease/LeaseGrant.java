package com.team4u.framework.lease;

/**
 * 表示任务已被某个 worker 成功持有租约。
 */
public class LeaseGrant {

    private final String taskId;
    private final String taskType;
    private final String payload;
    private final String workerId;
    private final String leaseToken;
    private final int attemptCount;
    private final long createdAtMillis;
    private final long visibleAtMillis;
    private final long leaseExpiresAtMillis;

    public LeaseGrant(String taskId,
                      String taskType,
                      String payload,
                      String workerId,
                      String leaseToken,
                      int attemptCount,
                      long createdAtMillis,
                      long visibleAtMillis,
                      long leaseExpiresAtMillis) {
        this.taskId = taskId;
        this.taskType = taskType;
        this.payload = payload;
        this.workerId = workerId;
        this.leaseToken = leaseToken;
        this.attemptCount = attemptCount;
        this.createdAtMillis = createdAtMillis;
        this.visibleAtMillis = visibleAtMillis;
        this.leaseExpiresAtMillis = leaseExpiresAtMillis;
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

    public String getWorkerId() {
        return workerId;
    }

    public String getLeaseToken() {
        return leaseToken;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public long getVisibleAtMillis() {
        return visibleAtMillis;
    }

    public long getLeaseExpiresAtMillis() {
        return leaseExpiresAtMillis;
    }
}
