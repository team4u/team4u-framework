package com.team4u.framework.lease.spi;

import com.team4u.framework.lease.api.Task;

public final class LeaseHandle {

    private final String taskId;
    private final String workerId;
    private final String leaseToken;

    private LeaseHandle(String taskId, String workerId, String leaseToken) {
        this.taskId = LeaseValues.requireText(taskId, "taskId");
        this.workerId = LeaseValues.requireText(workerId, "workerId");
        this.leaseToken = LeaseValues.requireText(leaseToken, "leaseToken");
    }

    public static LeaseHandle of(String taskId, String workerId, String leaseToken) {
        return new LeaseHandle(taskId, workerId, leaseToken);
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
