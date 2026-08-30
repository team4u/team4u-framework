package com.team4u.framework.fsm;

/**
 * 一次状态迁移的不可变执行结果。
 * <p>
 * 未迁移（未命中或守卫拒绝）时 {@link #getTo()} 与 {@link #getTransition()} 返回 {@code null}，
 * {@link #getState()} 仍返回来源状态，便于调用方直接继续使用。
 *
 * @param <S> 状态类型
 * @param <E> 事件类型
 * @param <C> 业务上下文类型
 * @author jay.wu
 */
public final class TransitionResult<S, E, C> {

    private final String machineId;
    private final TransitionOutcome outcome;
    private final S from;
    private final E event;
    private final S to;
    private final C context;
    private final Transition<S, E, C> transition;
    private final int evaluatedTransitionCount;

    private TransitionResult(String machineId,
                             TransitionOutcome outcome,
                             S from,
                             E event,
                             S to,
                             C context,
                             Transition<S, E, C> transition,
                             int evaluatedTransitionCount) {
        this.machineId = machineId;
        this.outcome = outcome;
        this.from = from;
        this.event = event;
        this.to = to;
        this.context = context;
        this.transition = transition;
        this.evaluatedTransitionCount = evaluatedTransitionCount;
    }

    static <S, E, C> TransitionResult<S, E, C> transitioned(String machineId,
                                                             S from,
                                                             E event,
                                                             S to,
                                                             C context,
                                                             Transition<S, E, C> transition,
                                                             int evaluatedTransitionCount) {
        return new TransitionResult<>(machineId, TransitionOutcome.TRANSITIONED, from, event, to,
                context, transition, evaluatedTransitionCount);
    }

    static <S, E, C> TransitionResult<S, E, C> rejected(String machineId,
                                                         TransitionOutcome outcome,
                                                         S from,
                                                         E event,
                                                         C context,
                                                         int evaluatedTransitionCount) {
        return new TransitionResult<>(machineId, outcome, from, event, null, context, null,
                evaluatedTransitionCount);
    }

    /**
     * 获取状态机标识。
     *
     * @return 状态机标识
     */
    public String getMachineId() {
        return machineId;
    }

    /**
     * 获取判定结果。
     *
     * @return 判定结果
     */
    public TransitionOutcome getOutcome() {
        return outcome;
    }

    /**
     * 获取来源状态。
     *
     * @return 来源状态
     */
    public S getFrom() {
        return from;
    }

    /**
     * 获取触发事件。
     *
     * @return 触发事件
     */
    public E getEvent() {
        return event;
    }

    /**
     * 获取目标状态。未迁移时返回 {@code null}。
     *
     * @return 目标状态
     */
    public S getTo() {
        return to;
    }

    /**
     * 获取回传的业务上下文，即调用方传入的原始引用。
     *
     * @return 业务上下文，可能为 {@code null}
     */
    public C getContext() {
        return context;
    }

    /**
     * 获取命中的迁移定义。未迁移时返回 {@code null}。
     *
     * @return 命中的迁移定义
     */
    public Transition<S, E, C> getTransition() {
        return transition;
    }

    /**
     * 获取命中迁移的标识。未迁移时返回 {@code null}。
     *
     * @return 迁移标识
     */
    public String getTransitionId() {
        return transition == null ? null : transition.getId();
    }

    /**
     * 获取本次判定中实际评估过的候选迁移数量（含无守卫规则），用于诊断与监控。
     *
     * @return 已评估的候选迁移数量
     */
    public int getEvaluatedTransitionCount() {
        return evaluatedTransitionCount;
    }

    /**
     * 判断迁移是否成功。
     *
     * @return 成功时返回 {@code true}
     */
    public boolean isAccepted() {
        return outcome.isAccepted();
    }

    /**
     * 判断迁移是否被拒绝（未命中或守卫拒绝）。
     *
     * @return 被拒绝时返回 {@code true}
     */
    public boolean isRejected() {
        return !isAccepted();
    }

    /**
     * 获取本次执行后的有效状态。迁移成功时为目标状态，否则仍为来源状态。
     *
     * @return 执行后的有效状态
     */
    public S getState() {
        return isAccepted() ? to : from;
    }

    @Override
    public String toString() {
        return "TransitionResult{" +
                "machineId='" + machineId + '\'' +
                ", outcome=" + outcome +
                ", from=" + from +
                ", event=" + event +
                ", to=" + to +
                ", transitionId=" + getTransitionId() +
                '}';
    }
}
