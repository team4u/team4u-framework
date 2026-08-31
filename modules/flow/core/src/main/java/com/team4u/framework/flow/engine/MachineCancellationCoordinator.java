package com.team4u.framework.flow.engine;
import com.team4u.framework.flow.model.Cancellation;

/**
 * 流程状态机取消状态协同器（Machine Cancellation Coordinator）。
 *
 * <p>确保取消信号处于最高裁决优先级（Cancellation Wins），在检测到取消时原子清空帧栈并标记为 CANCELLED 生命周期。</p>
 *
 * @author jay.wu
 */
public final class MachineCancellationCoordinator {
    private final MachineState state;
    private final Cancellation cancellation;

    MachineCancellationCoordinator(MachineState state, Cancellation cancellation) {
        this.state = state;
        this.cancellation = cancellation;
    }

    /**
     * 标记当前状态机已被取消。
     */
    void cancel() {
        markCancelled();
    }

    /**
     * 检查取消信号是否生效并赢得终态裁决。
     *
     * @return 若已取消并完成清空标记则返回 true
     */
    boolean cancellationWins() {
        if (!cancellation.isCancelled()) return false;
        markCancelled();
        return true;
    }

    private void markCancelled() {
        state.lifecycle(MachineState.Lifecycle.CANCELLED);
        state.outcome(null);
        state.awaitingPoint(null);
        state.pendingSignal(null);
        state.clearFrames();
    }
}

