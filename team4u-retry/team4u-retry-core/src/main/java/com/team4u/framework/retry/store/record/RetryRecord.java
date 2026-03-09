package com.team4u.framework.retry.store.record;

import com.team4u.framework.retry.domain.store.RetryRequest;
import com.team4u.framework.retry.domain.store.RetryState;
import lombok.Builder;
import lombok.Data;

/**
 * 完整的持久化重试记录。
 */
@Data
@Builder
public class RetryRecord {
    /**
     * 持久化生成的全局唯一任务 ID
     */
    private String taskId;
    /**
     * 原始任务请求信息
     */
    private RetryRequest request;
    /**
     * 当前流转状态
     */
    private RetryState state;
}
