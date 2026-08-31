package com.team4u.framework.flow;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local 模式下不透明的内存态续接句柄，单次消费。仅可由产生它的 LocalExecutable resume。
 */
public final class Suspension<O> {
    private final Object executableIdentity;
    private final MachineState state;
    /** 创建时快照的挂起点；resume 会清空 state.awaitingPoint，故此 getter 不再读共享可变字段。 */
    private final String resumePoint;
    private final AtomicBoolean consumed = new AtomicBoolean();

    Suspension(Object executableIdentity, MachineState state) {
        this.executableIdentity = Objects.requireNonNull(executableIdentity, "identity");
        this.state = Objects.requireNonNull(state, "state");
        this.resumePoint = state.awaitingPoint;
    }

    public String executionId() {
        return state.executionId;
    }

    public String resumePoint() {
        return resumePoint;
    }

    public boolean consumed() {
        return consumed.get();
    }

    boolean belongsTo(Object identity) {
        return executableIdentity == identity;
    }

    MachineState state() {
        return state;
    }

    boolean consume() {
        return consumed.compareAndSet(false, true);
    }
}
