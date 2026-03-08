package com.team4u.framework.lease.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 租约修改请求
 */
@Data
@Builder
public class LeaseUpdateRequest {

    /**
     * 全局唯一的任务 ID
     */
    private String taskId;

    /**
     * 任务类型
     */
    private String taskType;

    /**
     * 业务执行载荷
     */
    private String payload;

    /**
     * 任务优先级
     */
    private Integer priority;

    /**
     * 任务扩展属性
     */
    private Map<String, String> attributes;
}
