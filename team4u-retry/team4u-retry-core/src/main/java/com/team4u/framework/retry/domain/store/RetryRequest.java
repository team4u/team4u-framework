package com.team4u.framework.retry.domain.store;

import com.team4u.framework.retry.domain.RecoverySpec;
import com.team4u.framework.retry.policy.RetryPolicy;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 重试请求对象，表示“这个任务是什么”。
 */
@Data
@Builder
public class RetryRequest {
    private String taskId;
    private String handlerTaskType;
    private String idempotencyKey;
    private RecoverySpec recovery;
    private RetryPolicy policy;
    private Instant createdAt;
}
