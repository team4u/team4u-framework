package com.team4u.framework.retry.integration.lease;

import com.team4u.framework.lease.enums.LeaseAdminResult;
import lombok.Getter;

/**
 * lease 管理类操作未应用时抛出的异常。
 */
@Getter
public class LeaseAdminOperationException extends IllegalStateException {

    private final String operation;
    private final String taskId;
    private final LeaseAdminResult result;
    private final boolean retriable;

    public LeaseAdminOperationException(String operation, String taskId, LeaseAdminResult result) {
        super("Lease " + operation + " was not applied for taskId=" + taskId + ", result=" + result);
        this.operation = operation;
        this.taskId = taskId;
        this.result = result;
        this.retriable = result == LeaseAdminResult.ACTIVE_LEASE_PRESENT;
    }
}
