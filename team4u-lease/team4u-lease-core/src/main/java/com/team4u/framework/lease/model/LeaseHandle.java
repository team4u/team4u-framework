package com.team4u.framework.lease.model;

import lombok.Data;

/**
 * 租约操作句柄
 * <p>
 * 封装了任务 ID、工作者 ID 以及唯一的租约令牌。在对任务执行 Ack、Retry 或补心跳操作时，
 * 必须传递此句柄以证明当前操作者对任务的合法持有权。
 */
@Data
public class LeaseHandle {

    /**
     * 任务 ID
     */
    private final String taskId;
    /**
     * 持有租约的工作者 ID
     */
    private final String workerId;
    /**
     * 租约幂等令牌
     */
    private final String leaseToken;
}
