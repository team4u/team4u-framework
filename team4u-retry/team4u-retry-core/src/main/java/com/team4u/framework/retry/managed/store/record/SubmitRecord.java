package com.team4u.framework.retry.managed.store.record;

import lombok.Builder;
import lombok.Data;

/**
 * 任务提交（幂等建档）的初步结果。
 */
@Data
@Builder
public class SubmitRecord {

    /**
     * 标识本次提交是否真正创建了新记录。若为 false，则表示命中了已存在的任务（幂等生效）
     */
    private boolean created;

    /**
     * 关联的持久化重试记录详情
     */
    private RetryRecord record;
}
