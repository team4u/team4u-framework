package com.team4u.framework.flow.engine;

import com.team4u.framework.policy.api.KeyedPolicy;
import com.team4u.framework.flow.compiler.PlanNode;

/**
 * 运行时物理节点推进执行策略。
 *
 * @param <T> 物理节点类型
 * @author jay.wu
 */
public interface NodeExecutionHandler<T extends PlanNode> extends KeyedPolicy<Class<? extends PlanNode>> {

    /**
     * 执行当前物理节点。
     *
     * @param node    物理节点
     * @param frame   当前栈帧
     * @param machine 状态机实例
     * @return 若发生阻塞等待/挂起/中断则返回 MachineResult，否则返回 null 继续驱动状态机
     */
    MachineResult execute(T node, RuntimeFrame frame, SerialMachine machine);
}
