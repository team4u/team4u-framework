package com.team4u.framework.flow.engine;
import com.team4u.framework.flow.compiler.PlanNode;
import com.team4u.framework.flow.model.Outcome;

/**
 * 运行时帧栈结果归约与状态转移协调器（Runtime Stack Frame Reducer）。
 *
 * <p>核心职责：结合 {@link FrameReducePolicyRegistry} 将子节点产生的 {@link Outcome}
 * 委托给具体的父帧归约策略进行状态转移裁决。归约策略经 {@link SerialMachine} 的本机
 * 类型级缓存解析，避免热路径上的重复全局注册表查表。</p>
 *
 * @author jay.wu
 */
public final class FrameReducer {
    private FrameReducer() { }

    /**
     * 将子节点的四态结果交给父帧进行归约。
     *
     * @param machine 状态机实例
     * @param frame   当前父帧
     * @param child   子节点返回的四态结果
     * @return 若父帧自身已执行完成则返回父帧的最终 Outcome（向上传递）；若压入了新的子节点继续推进则返回 null
     */
    static Outcome<?> consume(SerialMachine machine, RuntimeFrame frame, Outcome<?> child) {
        FrameReducePolicy<PlanNode> policy = machine.frameReducePolicy(frame.node);
        return policy.reduce(frame.node, machine, frame, child);
    }
}
