package com.team4u.framework.lease.model;

import com.team4u.framework.lease.api.LeaseProducer;
import lombok.Builder;
import lombok.Data;

/**
 * 任务发布结果
 * <p>
 * 封装 {@link com.team4u.framework.lease.api.LeaseProducer#publishIfAbsent} 操作的返回结果。
 * 用于告知调用者本次请求是否成功创建了新任务，还是命中了已存在的幂等任务。
 */
@Data
@Builder
public class LeasePublishResult {
    /**
     * 是否创建了新任务
     * <p>
     * {@code true} - 成功创建了新任务<br>
     * {@code false} - 命中已存在的幂等任务（businessKey 重复），返回的是现有任务的信息
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
