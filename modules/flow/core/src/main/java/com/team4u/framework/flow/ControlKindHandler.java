package com.team4u.framework.flow;

import com.team4u.framework.policy.api.KeyedPolicy;

/**
 * 治理控制类型（POLICY / PERSISTENT_POLICY / RETRY / TIMEOUT）生命周期处理器策略。
 *
 * @author jay.wu
 */
interface ControlKindHandler extends KeyedPolicy<PlanNode.Control.Kind> {

    /**
     * 进入治理控制节点时的前置调度处理。
     *
     * @param control 控制物理节点
     * @param frame   当前控制帧
     * @param machine 状态机实例
     * @return 若发生阻塞等待/中断则返回 MachineResult，否则返回 null 继续推进主体
     */
    MachineResult enter(PlanNode.Control control, RuntimeFrame frame, SerialMachine machine);

    /**
     * 主体节点执行完成后，对子结果进行归约、退避计算或后置通知。
     *
     * @param control 控制物理节点
     * @param frame   当前控制帧
     * @param machine 状态机实例
     * @param child   主体节点返回的四态结果
     * @return 最终 Outcome 或 null（若压入重试帧）
     */
    Outcome<?> reduce(PlanNode.Control control, RuntimeFrame frame, SerialMachine machine, Outcome<?> child);
}
