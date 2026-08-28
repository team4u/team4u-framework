package com.team4u.framework.retry.managed.model;

import com.team4u.framework.retry.api.RecoverySpec;
import com.team4u.framework.retry.api.RetryPolicy;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 重试请求对象，表示“这个任务是什么”。
 */
@Data
@Builder
public class RetryRequest {
    /**
     * 持久化层面生成的全局唯一任务 ID
     */
    private String taskId;
    /**
     * 任务所属的业务类型
     */
    private String taskType;
    /**
     * 业务方提供的幂等键，确保相同业务任务不重复处理
     */
    private String idempotencyKey;
    /**
     * 任务恢复所需的静态元数据（载荷及处理器类型等）
     */
    private RecoverySpec recovery;
    /**
     * 该任务关联的重试策略
     */
    private RetryPolicy policy;
    /**
     * 任务首次持久化的落盘时间
     */
    private Instant createdAt;
}
