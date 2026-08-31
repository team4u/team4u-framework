package com.team4u.framework.flow;

import java.time.Instant;

/** 内核单次推进的结果快照，供投影层转译为 FlowResult。 */
final class MachineResult {
    private final MachineState.Lifecycle lifecycle;
    private final Outcome<?> outcome;
    private final String awaitingPoint;
    private final Instant wakeAt;

    public MachineResult(MachineState.Lifecycle lifecycle, Outcome<?> outcome,
                         String awaitingPoint, Instant wakeAt) {
        this.lifecycle = lifecycle;
        this.outcome = outcome;
        this.awaitingPoint = awaitingPoint;
        this.wakeAt = wakeAt;
    }

    static MachineResult from(MachineState state, Instant wakeAt) {
        return new MachineResult(state.lifecycle, state.outcome, state.awaitingPoint, wakeAt);
    }

    public MachineState.Lifecycle lifecycle() {
        return lifecycle;
    }

    public Outcome<?> outcome() {
        return outcome;
    }

    public String awaitingPoint() {
        return awaitingPoint;
    }

    public Instant wakeAt() {
        return wakeAt;
    }
}
