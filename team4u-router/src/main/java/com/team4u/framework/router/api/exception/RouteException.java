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
     * 错误码：类型不匹配
     */
    public static final String TYPE_MISMATCH = "TYPE_MISMATCH";

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

    /**
     * 创建类型不匹配异常
     *
     * @param beanName     Bean 名称
     * @param actualType   实际类型
     * @param expectedType 期望类型
     * @return 异常实例
     */
    public static RouteException typeMismatch(String beanName, Class<?> actualType, Class<?> expectedType) {
        return new RouteException(
                TYPE_MISMATCH,
                String.format("The routed bean [%s] is of type [%s], but expected type is [%s]",
                        beanName, actualType.getName(), expectedType.getName()));
    }
}
