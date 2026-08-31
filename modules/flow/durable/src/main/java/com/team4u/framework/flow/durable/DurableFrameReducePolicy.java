package com.team4u.framework.flow.durable;

import com.team4u.framework.policy.api.KeyedPolicy;

/**
 * Durable 运行时父帧状态与子节点结果归约策略。
 *
 * @param <T> 容器节点类型
 * @author jay.wu
 */
interface DurableFrameReducePolicy<T extends DurablePlanNode> extends KeyedPolicy<Class<? extends DurablePlanNode>> {

    /**
     * 将子节点的执行结果归约至当前父帧。
     *
     * @param node    父节点物理模型
     * @param frame   当前父帧
     * @param child   子节点返回的四态结果
     * @param machine Durable 状态机实例
     * @return 若父帧自身执行完成则返回最终 MachineOutcome；若压入了新的子节点继续推进则返回 null
     */
    DurableState.MachineOutcome reduce(T node, DurableState.RuntimeFrame frame, DurableState.MachineOutcome child, DurableMachine machine);
}
