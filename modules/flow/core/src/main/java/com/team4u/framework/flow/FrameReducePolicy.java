package com.team4u.framework.flow;

import com.team4u.framework.policy.api.KeyedPolicy;

/**
 * 运行时父帧状态与子节点结果归约策略。
 *
 * @param <T> 容器节点类型
 * @author jay.wu
 */
interface FrameReducePolicy<T extends PlanNode> extends KeyedPolicy<Class<? extends PlanNode>> {

    /**
     * 将子节点的执行结果归约至当前父帧。
     *
     * @param node    父节点物理模型
     * @param machine 状态机实例
     * @param frame   当前父帧
     * @param child   子节点返回的四态结果
     * @return 若父帧自身执行完成则返回最终 Outcome；若压入了新的子节点继续推进则返回 null
     */
    Outcome<?> reduce(T node, SerialMachine machine, RuntimeFrame frame, Outcome<?> child);
}
