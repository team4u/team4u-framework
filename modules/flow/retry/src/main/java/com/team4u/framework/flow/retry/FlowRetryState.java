package com.team4u.framework.flow.retry;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.Serializable;

/**
 * 流程重试不可变策略状态（支持 Durable 持久化存储与崩溃恢复）。
 *
 * @author jay.wu
 */
@Getter
@ToString
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public final class FlowRetryState implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 当前尝试轮次（首次执行为 1，第 1 次重试为 2，以此类推）。
     */
    private int attempt;

    /**
     * 获取初始重试状态（attempt = 1）。
     */
    public static FlowRetryState initial() {
        return new FlowRetryState(1);
    }

    /**
     * 生成下一轮次重试状态（attempt + 1）。
     */
    public FlowRetryState nextAttempt() {
        return new FlowRetryState(this.attempt + 1);
    }
}
