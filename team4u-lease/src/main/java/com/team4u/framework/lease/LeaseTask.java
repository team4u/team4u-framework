package com.team4u.framework.lease;

import lombok.*;

import java.util.Map;

/**
 * 通用租约任务模型。
 * <p>
 * 封装了任务在后端存储层中的完整信息，包括元数据、业务负载以及自定义属性。
 */
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class LeaseTask {

    /**
     * 任务的全局唯一标识
     */
    private final String taskId;
    /**
     * 任务类型，用于标识业务分类
     */
    private final String taskType;
    /**
     * 任务的业务负载数据内容
     */
    private final String payload;
    /**
     * 任务的原始创建毫秒时间戳
     */
    private final long createdAtMillis;
    /**
     * 任务当前对 Worker 可见或生效的时刻
     */
    private final long visibleAtMillis;
    /**
     * 任务已经历的获取/执行尝试次数
     */
    private final int attemptCount;
    /**
     * 任务的自定义扩展属性集合
     */
    @Singular
    private final Map<String, String> attributes;
}
