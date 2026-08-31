package com.team4u.framework.flow.engine;
import com.team4u.framework.flow.compiler.PlanNode;

/**
 * 环绕治理控制节点（Timeout / Policy / PersistentPolicy）进栈执行与前置判定调度器。
 *
 * <p>核心职责：结合 {@link ControlKindRegistry} 将进栈调度委托给具体的控制类型策略实现；
 * 策略经 {@link SerialMachine} 的本机 Kind 级缓存解析，避免热路径上的重复全局注册表查表。</p>
 *
 * @author jay.wu
 */
public final class ControlExecutor {
    private ControlExecutor() { }

    /**
     * 调度进入控制节点。
     *
     * @param machine 状态机实例
     * @param frame   当前控制帧
     * @param control 控制物理节点
     * @return 挂起/等待结果快照，若已直接压入子节点继续推进则返回 null
     */
    static MachineResult enter(SerialMachine machine, RuntimeFrame frame,
                               PlanNode.Control control) {
        return machine.controlKindHandler(control.kind()).enter(control, frame, machine);
    }
}
