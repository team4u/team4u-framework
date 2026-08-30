package com.team4u.framework.flow;

import java.util.Collections;

/**
 * 结构节点（Sequence/Route/Fallback/Await）进入帧栈的辅助逻辑：压入首个子帧或挂起。
 * 每个方法假定帧 phase==0 表示尚未进入，进入后置位以避免重复压栈。
 */
final class FrameEntrant {
    private FrameEntrant() { }

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

    static void route(SerialMachine machine, RuntimeFrame frame,
                      PlanNode.Route route) {
        if (frame.phase != 0) throw new IllegalStateException(
                "Route child frame is missing at " + route.descriptor().path());
        frame.phase = 1;
        machine.push(route.selector(), frame.entry);
    }

    static void fallback(SerialMachine machine, RuntimeFrame frame,
                         PlanNode.Fallback fallback) {
        if (frame.phase != 0) throw new IllegalStateException(
                "Fallback child frame is missing at " + fallback.descriptor().path());
        frame.phase = 1;
        frame.index = 0;
        machine.push(fallback.branches().get(0), frame.entry);
    }

    /** 有待处理 resume 信号则恢复执行，否则置 SUSPENDED 并返回挂起结果。 */
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
