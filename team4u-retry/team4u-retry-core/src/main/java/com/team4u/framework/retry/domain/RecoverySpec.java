package com.team4u.framework.retry.domain;

import lombok.Getter;

/**
 * 恢复数据规格定义。
 * 提交任务时，一次性构建用于后续潜在托管恢复时的载荷数据。
 */
@Getter
public class RecoverySpec {
    /**
     * 恢复处理器路由类型（需与所在环境能识别的 recovery handler 对应）。
     */
    private final String handlerTaskType;

    /**
     * 用于恢复执行此任务需要的数据（载荷）。
     */
    private final Object payload;

    public RecoverySpec(String handlerTaskType, Object payload) {
        this.handlerTaskType = handlerTaskType;
        this.payload = payload;
    }

    public static RecoverySpec of(String handlerTaskType, Object payload) {
        return new RecoverySpec(handlerTaskType, payload);
    }
}
