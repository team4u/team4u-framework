package com.team4u.framework.router.api.exception;

/**
 * 路由模块基础异常类 (Base Route Exception)
 * <p>
 * 它是框架内所有路由逻辑异常的超类，封装了统一的错误码（ErrorCode）机制，
 * 便于上游业务系统进行精细化的错误分类处理。
 * </p>
 *
 * @author jay.wu
 */
public class RouteException extends RuntimeException {

    /**
     * 错误码：类型不匹配
     */
    public static final String TYPE_MISMATCH = "TYPE_MISMATCH";
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
     * 创建类型不匹配异常。
     * 用于在动态定位 Bean 时，发现定位到的对象与接口期望的类型不符。
     *
     * @param beanName     Bean 实例名称
     * @param actualType   运行时实际检测到的类型
     * @param expectedType 路由代理或定位逻辑声明的接口类型
     * @return 类型不匹配异常实例
     */
    public static RouteException typeMismatch(String beanName, Class<?> actualType, Class<?> expectedType) {
        return new RouteException(
                TYPE_MISMATCH,
                String.format("The routed bean [%s] is of type [%s], but expected type is [%s]",
                        beanName, actualType.getName(), expectedType.getName()));
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
