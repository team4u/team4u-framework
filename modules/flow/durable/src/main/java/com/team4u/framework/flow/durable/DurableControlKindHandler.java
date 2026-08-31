package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.ControlKind;
import com.team4u.framework.policy.api.KeyedPolicy;

/**
 * Durable 治理控制类型（POLICY / PERSISTENT_POLICY / RETRY / TIMEOUT）生命周期处理器策略。
 *
 * @author jay.wu
 */
interface DurableControlKindHandler extends KeyedPolicy<ControlKind> {

    /**
     * 进入治理控制节点时的前置调度处理。
     */
    void enter(DurablePlanNode.Control control, DurableState.RuntimeFrame frame, DurableMachine machine);

    /**
     * 主体节点执行完成后，对子结果进行归约、退避计算或后置通知。
     */
    DurableState.MachineOutcome reduce(DurablePlanNode.Control control, DurableState.RuntimeFrame frame, DurableState.MachineOutcome child, DurableMachine machine);
}
