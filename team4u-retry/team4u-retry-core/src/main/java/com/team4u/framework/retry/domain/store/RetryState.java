package com.team4u.framework.retry.domain.store;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 重试状态对象，表示“这个任务现在执行到哪了”。
 */
@Data
@Builder
public class RetryState {
    private int attempts;
    private RetryStatus status;
    private Instant nextRunAt;
    private String lastErrorCode;
    private String lastErrorMessage;
}
