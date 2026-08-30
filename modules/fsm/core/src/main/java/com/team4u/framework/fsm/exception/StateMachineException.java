package com.team4u.framework.fsm.exception;

/**
 * 状态机异常基类。
 *
 * @author jay.wu
 */
public class StateMachineException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StateMachineException(String message) {
        super(message);
    }

    public StateMachineException(String message, Throwable cause) {
        super(message, cause);
    }
}
