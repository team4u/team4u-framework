package com.team4u.framework.flow.durable;

import com.team4u.framework.policy.api.KeyedPolicy;

/**
 * Durable 物理节点进栈推进执行策略。
 *
 * @param <T> Durable 物理节点类型
 * @author jay.wu
 */
interface DurableNodeExecutionHandler<T extends DurablePlanNode> extends KeyedPolicy<Class<? extends DurablePlanNode>> {

    /**
     * 执行当前物理节点。
     *
     * @param node    物理节点
     * @param frame   当前栈帧
     * @param machine Durable 状态机实例
     */
    void execute(T node, DurableState.RuntimeFrame frame, DurableMachine machine);
}
