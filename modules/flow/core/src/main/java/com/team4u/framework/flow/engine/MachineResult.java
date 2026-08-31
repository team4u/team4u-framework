package com.team4u.framework.flow.engine;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.time.Instant;
import com.team4u.framework.flow.Local;
import com.team4u.framework.flow.model.Outcome;

/**
 * 流程状态机（{@link SerialMachine}）单次驱动推进的结果快照。
 *
 * <p>包含生命周期阶段、业务四态结果、等待挂起点标识以及下一次计划唤醒时间戳，供 Local/Durable 投影层转译为外层结果。</p>
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@AllArgsConstructor
public final class MachineResult {
    /** 状态机生命周期阶段。 */
    private final MachineState.Lifecycle lifecycle;
    /** 最终落定的业务四态结果（仅在 COMPLETED 时有效）。 */
    private final Outcome<?> outcome;
    /** 正在等待的挂起点名称（仅在 SUSPENDED 时有效）。 */
    private final String awaitingPoint;
    /** 建议的退避唤醒绝对时间点（若有）。 */
    private final Instant wakeAt;

    /**
     * 根据当前状态机快照与唤醒时间构造结果对象。
     *
     * @param state  状态机状态
     * @param wakeAt 唤醒时间点
     * @return 结果快照
     */
    static MachineResult from(MachineState state, Instant wakeAt) {
        return new MachineResult(state.lifecycle(), state.outcome(), state.awaitingPoint(), wakeAt);
    }
}

