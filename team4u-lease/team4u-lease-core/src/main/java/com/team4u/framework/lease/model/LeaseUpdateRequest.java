package com.team4u.framework.lease.model;

import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 租约任务更新请求模型
 * <p>
 * 用于管理面运维操作，支持对已存在任务的元数据进行修改。
 * 可更新任务类型、负载内容、优先级及扩展属性。
 * <p>
 * 该操作通常用于：
 * <ul>
 *     <li>修正错误配置的任务参数</li>
 *     <li>更新任务负载内容以适配新的业务逻辑</li>
 *     <li>调整任务优先级以改变执行顺序</li>
 * </ul>
 * <p>
 * <b>注意：</b>更新操作要求任务处于非终态（非 CLOSED）或租约已过期状态，
 * 若任务当前持有有效租约，更新将失败。
 */
@Data
public class LeaseUpdateRequest {

    /**
     * 全局唯一的任务 ID
     */
    private final String taskId;

    /**
     * 新的任务类型（可选）
     */
    private final String taskType;

    /**
     * 新的业务执行载荷（可选）
     */
    private final String payload;

    /**
     * 新的任务优先级（可选）
     */
    private final Integer priority;

    /**
     * 新的扩展属性（可选）
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
