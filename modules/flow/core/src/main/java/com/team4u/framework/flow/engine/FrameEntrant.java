package com.team4u.framework.flow.engine;

import java.util.Collections;
import com.team4u.framework.flow.api.FlowObserver;
import com.team4u.framework.flow.compiler.PlanNode;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Resumed;
import com.team4u.framework.flow.model.Suspension;

/**
 * 结构复合节点（Sequence / Route / Fallback / Await）进入与子帧入栈调度器（Frame Entrant Dispatcher）。
 *
 * <p>负责在状态机遇到结构节点时，完成初始状态设置、首个子帧压栈（Push）以及挂起点拦截（Await Suspension）。</p>
 *
 * @author jay.wu
 */
public final class FrameEntrant {
    private FrameEntrant() { }

    /**
     * 顺序流水线节点进栈处理：压入首个子节点执行。
     *
     * @param machine  状态机实例
     * @param frame    当前流水线帧
     * @param sequence 顺序物理节点
     */
    static void sequence(SerialMachine machine, RuntimeFrame frame,
                         PlanNode.Sequence sequence) {
        if (frame.phase != 0) throw new IllegalStateException(
                "Sequence child frame is missing at " + sequence.descriptor().path());
        if (sequence.children().isEmpty()) {
            machine.finish(Outcome.accepted(frame.entry));
            return;
        }
        frame.phase = 1;
        frame.index = 0;
        machine.push(sequence.children().get(0), frame.current);
    }

    /**
     * 条件路由节点进栈处理：压入选择器（Selector）计算路由判别键。
     *
     * @param machine 状态机实例
     * @param frame   当前路由帧
     * @param route   路由物理节点
     */
    static void route(SerialMachine machine, RuntimeFrame frame,
                      PlanNode.Route route) {
        if (frame.phase != 0) throw new IllegalStateException(
                "Route child frame is missing at " + route.descriptor().path());
        frame.phase = 1;
        machine.push(route.selector(), frame.entry);
    }

    /**
     * 降级恢复节点进栈处理：压入首个候选分支执行。
     *
     * @param machine  状态机实例
     * @param frame    当前降级帧
     * @param fallback 降级物理节点
     */
    static void fallback(SerialMachine machine, RuntimeFrame frame,
                         PlanNode.Fallback fallback) {
        if (frame.phase != 0) throw new IllegalStateException(
                "Fallback child frame is missing at " + fallback.descriptor().path());
        frame.phase = 1;
        frame.index = 0;
        machine.push(fallback.branches().get(0), frame.entry);
    }

    /**
     * 挂起点拦截处理：若有外部恢复信号则组合为 Resumed 继续完成；若无则使流程进入 SUSPENDED 挂起态。
     *
     * @param machine 状态机实例
     * @param state   状态机状态
     * @param frame   当前挂起帧
     * @param await   挂起物理节点
     * @return 挂起结果快照，若已注入信号恢复则返回 null 继续推进
     */
    static MachineResult await(SerialMachine machine, MachineState state,
                               RuntimeFrame frame, PlanNode.Await await) {
        if (state.pendingSignal != null) {
            if (!await.point().name().equals(state.awaitingPoint))
                throw new IllegalStateException(
                        "Pending resume point does not match Await frame");
            Object signal = state.pendingSignal;
            state.pendingSignal = null;
            state.awaitingPoint = null;
            Resumed<Object, Object> resumed = new Resumed<Object, Object>(frame.entry, signal);
            machine.finish(Outcome.accepted(resumed));
            return null;
        }
        state.lifecycle = MachineState.Lifecycle.SUSPENDED;
        state.awaitingPoint = await.point().name();
        machine.event(FlowObserver.Type.FLOW_SUSPENDED, await.descriptor(),
                Collections.singletonMap("resumePoint", await.point().name()));
        // 发完 FLOW_SUSPENDED 后补检取消：observer 回调或并发线程可能在此窗口触发取消
        if (machine.cancelled()) {
            machine.cancel();
        }
        return MachineResult.from(state, null);
    }
}

