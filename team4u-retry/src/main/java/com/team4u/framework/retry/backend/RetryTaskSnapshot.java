package com.team4u.framework.retry.backend;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 重试任务快照
 * <p>
 * 包含跨节点恢复方法调用所需的元数据及重试进度。
 *
 * @author jay.wu
 */
@Getter
@Setter
@NoArgsConstructor
public class RetryTaskSnapshot {
    /**
     * 任务全局唯一 ID
     */
    private String taskId;
    /**
     * 任务类型标识
     */
    private String taskType;
    /**
     * 已执行尝试次数
     */
    private int executedAttempts;
    /**
     * 最大尝试次数限制（-1 表示无限制）
     */
    private int maxAttempts;
    /**
     * 任务创建时间戳
     */
    private long createdAt = System.currentTimeMillis();

    /**
     * Bean 标识符
     */
    private String beanName;
    /**
     * 方法名称
     */
    private String methodName;
    /**
     * 参数类型列表
     */
    private List<String> argTypes;
    /**
     * 序列化后的参数值列表
     */
    private List<String> argJsonValues;

}
