package com.team4u.framework.lease;

/**
 * 持有有效租约后用于运行时写回的句柄。
 */
public class LeaseHandle {

    private final String taskId;
    private final String workerId;
    private final String leaseToken;

    public LeaseHandle(String taskId, String workerId, String leaseToken) {
        this.taskId = taskId;
        this.workerId = workerId;
        this.leaseToken = leaseToken;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getWorkerId() {
        return workerId;
    }

    public String getLeaseToken() {
        return leaseToken;
    }
}
