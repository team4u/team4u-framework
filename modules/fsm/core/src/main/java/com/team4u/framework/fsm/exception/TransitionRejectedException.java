package com.team4u.framework.fsm.exception;

import com.team4u.framework.fsm.TransitionOutcome;
import com.team4u.framework.fsm.TransitionResult;

/**
 * 严格执行未能找到可执行迁移时抛出的异常。
 * <p>
 * {@link #getOutcome()} 区分“没有任何候选迁移”与“守卫全部拒绝”两种情况。
 *
 * @author jay.wu
 */
public final class TransitionRejectedException extends StateMachineException {

    private static final long serialVersionUID = 1L;

    private final String machineId;
    private final Object state;
    private final Object event;
    private final TransitionOutcome outcome;

    /**
     * 基于被拒绝的迁移结果创建异常。
     *
     * @param result 被拒绝的迁移结果
     */
    public TransitionRejectedException(TransitionResult<?, ?, ?> result) {
        super("State machine transition rejected|machineId=" + result.getMachineId()
                + "|state=" + ExceptionDiagnostics.describe(result.getFrom())
                + "|event=" + ExceptionDiagnostics.describe(result.getEvent())
                + "|outcome=" + result.getOutcome());
        this.machineId = result.getMachineId();
        this.state = result.getFrom();
        this.event = result.getEvent();
        this.outcome = result.getOutcome();
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
     * 获取被拒绝时的当前状态。
     *
     * @return 当前状态
     */
    public Object getState() {
        return state;
    }

    /**
     * 获取被拒绝时的事件。
     *
     * @return 触发事件
     */
    public Object getEvent() {
        return event;
    }

    /**
     * 获取拒绝类型。
     *
     * @return 拒绝类型
     */
    public TransitionOutcome getOutcome() {
        return outcome;
    }
}
