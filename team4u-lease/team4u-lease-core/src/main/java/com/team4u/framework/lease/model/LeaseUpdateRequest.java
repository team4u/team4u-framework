package com.team4u.framework.lease.model;

import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 租约修改请求
 */
@Data
public class LeaseUpdateRequest {

    /**
     * 全局唯一的任务 ID
     */
    private final String taskId;

    /**
     * 任务类型
     */
    private final String taskType;

    /**
     * 业务执行载荷
     */
    private final String payload;

    /**
     * 任务优先级
     */
    private final Integer priority;

    /**
     * 任务扩展属性
     */
    private final Map<String, String> attributes;

    @Builder
    public LeaseUpdateRequest(String taskId,
                              String taskType,
                              String payload,
                              Integer priority,
                              Map<String, String> attributes) {
        this.taskId = taskId;
        this.taskType = taskType;
        this.payload = payload;
        this.priority = priority;
        if (attributes == null) {
            this.attributes = Collections.emptyMap();
        } else {
            this.attributes = Collections.unmodifiableMap(new LinkedHashMap<String, String>(attributes));
        }
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }
}
