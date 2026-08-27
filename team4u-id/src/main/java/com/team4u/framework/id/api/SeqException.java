package com.team4u.framework.id.api;

/**
 * 序号组件异常基类
 *
 * @author jay.wu
 */
public class SeqException extends RuntimeException {

    public SeqException(String message) {
        super(message);
    }

    public SeqException(String message, Throwable cause) {
        super(message, cause);
    }
}
