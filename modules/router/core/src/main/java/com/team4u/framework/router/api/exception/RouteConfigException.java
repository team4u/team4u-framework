package com.team4u.framework.router.api.exception;

/**
 * 路由配置异常
 * <p>
 * 当路由配置解析失败或配置不合法时抛出。
 * </p>
 *
 * @author jay.wu
 */
public class RouteConfigException extends RouteException {

    /**
     * 错误码：配置解析失败
     */
    public static final String PARSE_ERROR = "PARSE_ERROR";
    /**
     * 错误码：配置验证失败
     */
    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    /**
     * 错误码：不支持的类型
     */
    public static final String UNSUPPORTED_TYPE = "UNSUPPORTED_TYPE";
    /**
     * 错误码：重复的配置
     */
    public static final String DUPLICATE_CONFIG = "DUPLICATE_CONFIG";
    private static final long serialVersionUID = 1L;
    private final String policyId;

    public RouteConfigException(String message) {
        super(message);
        this.policyId = null;
    }

    public RouteConfigException(String errorCode, String message) {
        super(errorCode, message);
        this.policyId = null;
    }

    public RouteConfigException(String errorCode, String policyId, String message) {
        super(errorCode, message);
        this.policyId = policyId;
    }

    public RouteConfigException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
        this.policyId = null;
    }

    /**
     * 创建不支持的类型异常
     *
     * @param type 不支持的类型
     * @return 异常实例
     */
    public static RouteConfigException unsupportedType(String type) {
        return new RouteConfigException(
                UNSUPPORTED_TYPE,
                String.format("Unsupported router type: %s", type));
    }

    /**
     * 创建配置解析失败异常
     *
     * @param message 错误信息
     * @return 异常实例
     */
    public static RouteConfigException parseError(String message) {
        return new RouteConfigException(PARSE_ERROR, message);
    }

    /**
     * 创建配置解析失败异常
     *
     * @param message 错误信息
     * @param cause   原因
     * @return 异常实例
     */
    public static RouteConfigException parseError(String message, Throwable cause) {
        return new RouteConfigException(PARSE_ERROR, message, cause);
    }

    /**
     * 创建配置验证失败异常
     *
     * @param message 错误信息
     * @return 异常实例
     */
    public static RouteConfigException validationError(String message) {
        return new RouteConfigException(VALIDATION_ERROR, message);
    }

    /**
     * 创建配置验证失败异常
     *
     * @param policyId 策略 ID
     * @param message  错误信息
     * @return 异常实例
     */
    public static RouteConfigException validationError(String policyId, String message) {
        return new RouteConfigException(VALIDATION_ERROR, policyId, message);
    }

    /**
     * 创建重复配置异常
     *
     * @param policyId      策略 ID
     * @param condition     重复的条件
     * @param existingValue 已存在的值
     * @param newValue      新值
     * @return 异常实例
     */
    public static RouteConfigException duplicateCondition(String policyId, String condition,
                                                          Object existingValue, Object newValue) {
        return new RouteConfigException(
                DUPLICATE_CONFIG,
                policyId,
                String.format("Invalid configuration in RoutePolicy [%s]: Duplicate condition key '%s' found. " +
                                "Cannot map to both [%s] and [%s].",
                        policyId, condition, existingValue, newValue));
    }

    /**
     * 获取策略 ID
     *
     * @return 策略 ID，可能为 null
     */
    public String getPolicyId() {
        return policyId;
    }
}
