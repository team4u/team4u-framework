package com.team4u.framework.fsm;

/**
 * 守卫与动作共享的不可变迁移上下文。
 * <p>
 * {@link #getTo()} 是构建期决定的目标状态：保持原状态的迁移返回来源状态，
 * 动作无法改写目标状态。
 *
 * @param <S> 状态类型
 * @param <E> 事件类型
 * @param <C> 业务上下文类型
 * @author jay.wu
 */
public final class TransitionContext<S, E, C> {

    private final String machineId;
    private final String transitionId;
    private final S from;
    private final E event;
    private final S to;
    private final C context;

    TransitionContext(String machineId, String transitionId, S from, E event, S to, C context) {
        this.machineId = machineId;
        this.transitionId = transitionId;
        this.from = from;
        this.event = event;
        this.to = to;
        this.context = context;
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
     * 获取命中的迁移标识。
     *
     * @return 迁移标识
     */
    public String getTransitionId() {
        return transitionId;
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
     * 获取本次迁移的目标状态。保持原状态的迁移返回来源状态。
     *
     * @return 目标状态
     */
    public S getTo() {
        return to;
    }

    /**
     * 获取调用方传入的业务上下文。该值允许为 {@code null}。
     *
     * @return 业务上下文
     */
    public C getContext() {
        return context;
    }

    @Override
    public String toString() {
        return "TransitionContext{" +
                "machineId='" + machineId + '\'' +
                ", transitionId='" + transitionId + '\'' +
                ", from=" + from +
                ", event=" + event +
                ", to=" + to +
                '}';
    }
}
