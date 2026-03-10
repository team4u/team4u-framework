package com.team4u.framework.lease.model;

import lombok.Builder;
import lombok.Data;

/**
 * 发布或命中已有任务时的返回值。
 */
@Data
@Builder
public class LeasePublishResult {
    /**
     * 当前请求是否成功触发了新任务的创建。
     * <p>
     * 若为 {@code false}，由于幂等键生效，表示命中并返回了一个现有任务。
     */
    private final boolean created;
    /**
     * 任务在租约系统内部的唯一标识 ID
     */
    private final String taskId;
    /**
     * 任务的完整详细记录，包含当前快照及元数据
     */
    private final LeaseTaskRecord record;
}
