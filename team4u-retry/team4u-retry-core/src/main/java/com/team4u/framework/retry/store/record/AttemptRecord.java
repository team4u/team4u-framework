package com.team4u.framework.retry.store.record;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 任务执行尝试记录。
 */
@Data
@Builder
public class AttemptRecord {
    /**
     * 执行时间
     */
    private Instant attemptAt;
    /**
     * 执行当前任务的 Worker 或节点标识
     */
    private String workerId;
}
