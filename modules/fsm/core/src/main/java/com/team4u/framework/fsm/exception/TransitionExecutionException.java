package com.team4u.framework.fsm.exception;

import com.team4u.framework.fsm.TransitionContext;

/**
 * 守卫或动作执行失败时抛出的异常。
 * <p>
 * 原始异常始终作为 cause 保留；{@link #getPhase()} 标明失败发生在守卫判定阶段
 * 还是动作执行阶段，{@link #getTransitionContext()} 携带失败时的完整迁移上下文
 * （状态机标识、迁移标识、来源状态、事件、目标状态）。
 * <p>
 * 守卫或动作一旦抛出异常即快速失败：引擎不会再尝试任何后续候选规则。
 *
 * @author jay.wu
 */
public final class TransitionExecutionException extends StateMachineException {

    private static final long serialVersionUID = 1L;

    /**
     * 发生异常的执行阶段。
     */
    public enum Phase {

        /**
         * 守卫判定阶段。
         */
        GUARD,

        /**
         * 动作执行阶段。
         */
        ACTION
    }

    private final TransitionContext<?, ?, ?> transitionContext;
    private final Phase phase;

    /**
     * 创建执行异常。
     *
     * @param transitionContext 失败时的迁移上下文
     * @param phase             失败阶段
     * @param cause             原始异常
     */
    public TransitionExecutionException(TransitionContext<?, ?, ?> transitionContext,
                                        Phase phase,
                                        Throwable cause) {
        super("State machine transition failed|machineId=" + transitionContext.getMachineId()
                + "|transitionId=" + transitionContext.getTransitionId()
                + "|phase=" + phase
                + "|from=" + ExceptionDiagnostics.describe(transitionContext.getFrom())
                + "|event=" + ExceptionDiagnostics.describe(transitionContext.getEvent())
                + "|to=" + ExceptionDiagnostics.describe(transitionContext.getTo()), cause);
        this.transitionContext = transitionContext;
        this.phase = phase;
    }

    /**
     * 获取状态机标识。
     *
     * @return 状态机标识
     */
    public String getMachineId() {
        return transitionContext.getMachineId();
    }

    /**
     * 获取失败迁移的标识。
     *
     * @return 迁移标识
     */
    public String getTransitionId() {
        return transitionContext.getTransitionId();
    }

    /**
     * 获取失败阶段。
     *
     * @return 失败阶段
     */
    public Phase getPhase() {
        return phase;
    }

    /**
     * 获取失败时的来源状态。
     *
     * @return 来源状态
     */
    public Object getFrom() {
        return transitionContext.getFrom();
    }

    /**
     * 获取失败时的触发事件。
     *
     * @return 触发事件
     */
    public Object getEvent() {
        return transitionContext.getEvent();
    }

    /**
     * 获取失败时迁移的目标状态。
     *
     * @return 目标状态
     */
    public Object getTo() {
        return transitionContext.getTo();
    }

    /**
     * 获取失败时的完整迁移上下文。
     *
     * @return 迁移上下文
     */
    public TransitionContext<?, ?, ?> getTransitionContext() {
        return transitionContext;
    }
}
