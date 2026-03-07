package com.team4u.framework.lease;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务查询记录模型
 * <p>
 * 该模型面向控制面板及运维 API，提供了任务全方位的静态与运行时元数据，
 * 包括执行状态、投递统计以及最近一次失败的错误堆栈信息。
 */
@Getter
@Builder(toBuilder = true)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class LeaseTaskRecord {

    /**
     * 任务全局唯一 ID
     */
    private final String taskId;
    /**
     * 所属队列
     */
    private final String queue;
    /**
     * 业务定义的任务类型
     */
    private final String taskType;
    /**
     * 原始业务执行载荷
     */
    private final String payload;
    /**
     * 当前任务生命周期状态
     */
    private final LeaseTaskStatus status;
    /**
     * 当前持有该任务的 Worker ID（仅在 LEASED 状态下有效）
     */
    private final String workerId;
    /**
     * 任务优先级
     */
    private final int priority;
    /**
     * 累计投递次数
     */
    private final int deliveryCount;
    /**
     * 累计失败次数
     */
    private final int failureCount;
    /**
     * 任务创建时间
     */
    private final long createdAtMillis;
    /**
     * 下次预期的可见/执行时间
     */
    private final long visibleAtMillis;
    /**
     * 租约截止时间
     */
    private final long leaseExpiresAtMillis;
    /**
     * 最近一次异常失败的关键错误信息
     */
    private final String lastError;
    /**
     * 任务扩展属性映射
     */
    private final Map<String, String> attributes;

    public Map<String, String> getAttributes() {
        if (attributes == null) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<String, String>(attributes));
    }
}
