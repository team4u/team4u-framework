package com.team4u.framework.retry.backend;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 重试任务快照
 * <p>
 * 包含了跨节点还原方法调用所需的所有元数据以及重试进度状态。
 *
 * @author jay.wu
 */
@Getter
@Setter
@NoArgsConstructor
public class RetryTaskSnapshot {
    /**
     * 任务全局唯一 ID，用于幂等去重
     */
    private String taskId;
    /**
     * 任务类型标识（Policy Key）
     */
    private String taskType;
    /**
     * 已执行尝试次数
     */
    private int executedAttempts;
    /**
     * 总尝试上限（-1 表示无限）
     */
    private int maxAttempts;
    /**
     * 任务首次创建时间戳
     */
    private long createdAt = System.currentTimeMillis();

    /**
     * Bean 标识符（对应 BeanManager 中的名称或类全限定名）
     */
    private String beanName;
    /**
     * 方法名
     */
    private String methodName;
    /**
     * 参数类型全限定名列表
     */
    private List<String> argTypes;
    /**
     * 参数 JSON 序列化后的字符串列表
     */
    private List<String> argJsonValues;

}
