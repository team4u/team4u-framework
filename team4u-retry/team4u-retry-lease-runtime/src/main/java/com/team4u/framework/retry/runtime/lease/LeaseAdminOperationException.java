package com.team4u.framework.retry.runtime.lease;

import com.team4u.framework.lease.enums.LeaseAdminResult;
import lombok.Getter;

/**
 * 租约管理操作异常。
 * <p>
 * 当向租约系统发起管理类操作（如关闭、重调度、更新）未能成功应用（即结果非 APPLIED）时抛出。
 * </p>
 */
@Getter
public class LeaseAdminOperationException extends IllegalStateException {

    /**
     * 触发异常的原子操作名称
     */
    private final String operation;
    /**
     * 关联的任务全局唯一 ID
     */
    private final String taskId;
    /**
     * 后端系统返回的具体执行状态码
     */
    private final LeaseAdminResult result;
    /**
     * 该操作是否具备可重试性（例如由于任务正在持有活跃租约运行中导致的临时性冲突）
     */
    private final boolean retriable;

    public LeaseAdminOperationException(String operation, String taskId, LeaseAdminResult result) {
        super("Lease " + operation + " was not applied for taskId=" + taskId + ", result=" + result);
        this.operation = operation;
        this.taskId = taskId;
        this.result = result;
        // 若因当前任务正在运行而导致管理操作失败，通常被视为可恢复的临时状态
        this.retriable = result == LeaseAdminResult.ACTIVE_LEASE_PRESENT;
    }
}
