package com.team4u.framework.retry.worker;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 可被 Worker 消费的重试任务记录。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RetryTaskRecord {

    public static final String PENDING = "PENDING";
    public static final String QUEUED = "QUEUED";
    public static final String TERMINAL = "TERMINAL";

    /**
     * intent / task 唯一标识
     */
    private String intentId;
    /**
     * 任务类型
     */
    private String taskType;
    /**
     * 恢复载荷
     */
    private String payload;
    /**
     * 创建时间
     */
    private long createdAt;
    /**
     * 下次可执行时间点
     */
    private long executeAtMillis;
    /**
     * PENDING / QUEUED / TERMINAL
     */
    private String status;
    /**
     * 最近一次失败原因
     */
    private String lastError;

    public RetryTaskRecord copy() {
        return new RetryTaskRecord(
                intentId,
                taskType,
                payload,
                createdAt,
                executeAtMillis,
                status,
                lastError
        );
    }
}
