package com.team4u.framework.router.api.exception;

/**
 * 路由模块基础异常类
 * <p>
 * 所有路由相关的异常都应继承此类，提供统一的异常处理机制。
 * </p>
 *
 * @author jay.wu
 */
public class RouteException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 错误码
     */
    private final String errorCode;

    public RouteException(String message) {
        super(message);
        this.errorCode = null;
    }

    public RouteException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public RouteException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = null;
    }

    public RouteException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * 获取错误码
     *
     * @return 错误码，可能为 null
     */
    public String getErrorCode() {
        return errorCode;
    }
}
