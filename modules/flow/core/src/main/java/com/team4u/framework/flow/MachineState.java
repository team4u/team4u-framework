package com.team4u.framework.flow;

import java.util.ArrayList;
import java.util.Objects;

/**
 * 内核可变运行状态：帧栈、生命周期、最终结果、挂起点与待处理 resume 信号。
 * 非线程安全，由单个 SerialMachine 独占。
 */
final class MachineState {
    /** 执行生命周期状态机：ACTIVE 推进中、SUSPENDED 挂起、COMPLETED/CANCELLED 终态。 */
    enum Lifecycle { ACTIVE, SUSPENDED, COMPLETED, CANCELLED }

    Lifecycle lifecycle = Lifecycle.ACTIVE;
    final ArrayList<RuntimeFrame> frames;
    final String executionId;
    Outcome<?> outcome;
    String awaitingPoint;
    Object pendingSignal;

    MachineState(PlanNode root, String executionId, Object input) {
        this.executionId = text(executionId);
        Objects.requireNonNull(input, "flow input must not be null");
        frames = new ArrayList<RuntimeFrame>();
        frames.add(new RuntimeFrame(root, input));
    }

    private static String text(String value) {
        Objects.requireNonNull(value, "executionId must not be null");
        if (value.trim().isEmpty()) throw new IllegalArgumentException("executionId must not be blank");
        return value;
    }
}
