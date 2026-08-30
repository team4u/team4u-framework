package com.team4u.framework.flow;

/**
 * 内核的取消协调器：保证取消态优先于其他终态落定。
 */
final class MachineCancellationCoordinator {
    private final MachineState state;
    private final Cancellation cancellation;

    MachineCancellationCoordinator(MachineState state, Cancellation cancellation) {
        this.state = state;
        this.cancellation = cancellation;
    }

    void cancel() {
        markCancelled();
    }

    boolean cancellationWins() {
        if (!cancellation.isCancelled()) return false;
        markCancelled();
        return true;
    }

    private void markCancelled() {
        state.lifecycle = MachineState.Lifecycle.CANCELLED;
        state.outcome = null;
        state.awaitingPoint = null;
        state.pendingSignal = null;
        state.frames.clear();
    }
}
