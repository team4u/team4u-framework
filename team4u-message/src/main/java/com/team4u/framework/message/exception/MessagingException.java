package com.team4u.framework.message.exception;

/**
 * 消息框架统一异常类
 *
 * @author jay.wu
 */
public class MessagingException extends RuntimeException {

    public MessagingException(String message) {
        super(message);
    }

    public MessagingException(String message, Throwable cause) {
        super(message, cause);
    }

    public MessagingException(Throwable cause) {
        super(cause);
    }
}
