package com.team4u.framework.retry.store.record;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 任务进入处理中（RUNNING）状态时的上下文快照。
 */
@Data
@Builder
public class ProcessingRecord {

    /**
     * 本次执行对应的尝试序号
     */
    private int attempts;

    /**
     * 本次开始处理的时间戳
     */
    private Instant processingAt;

    /**
     * 处理该任务的节点或工作线程唯一标识
     */
    private String workerId;

    /**
     * 触发本次执行的后台任务 ID
     */
    private String backendTaskId;
}
