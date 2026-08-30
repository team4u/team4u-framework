package com.team4u.framework.router.api.exception;

/**
 * 路由未找到异常
 * <p>
 * 当路由 ID 对应的路由器不存在或路由规则未匹配且无兜底配置时抛出。
 * </p>
 *
 * @author jay.wu
 */
public class RouteNotFoundException extends RouteException {

    /**
     * 错误码：路由器未找到
     */
    public static final String ROUTER_NOT_FOUND = "ROUTER_NOT_FOUND";
    /**
     * 错误码：规则未匹配
     */
    public static final String RULE_NOT_MATCHED = "RULE_NOT_MATCHED";
    /**
     * 错误码：Bean 未找到
     */
    public static final String BEAN_NOT_FOUND = "BEAN_NOT_FOUND";
    private static final long serialVersionUID = 1L;
    private final String routerId;

    public RouteNotFoundException(String routerId) {
        super(ROUTER_NOT_FOUND, String.format("Router [%s] not found", routerId));
        this.routerId = routerId;
    }

    public RouteNotFoundException(String errorCode, String routerId, String message) {
        super(errorCode, message);
        this.routerId = routerId;
    }

    /**
     * 创建路由规则未匹配异常。
     *
     * @param routerId 路由策略 ID
     * @return 异常实例
     */
    public static RouteNotFoundException ruleNotMatched(String routerId) {
        return new RouteNotFoundException(
                RULE_NOT_MATCHED,
                routerId,
                String.format("No matching rule or fallback configuration found for router ID [%s]", routerId)
        );
    }

    /**
     * 创建目标 Bean 未找到异常。
     *
     * @param routerId       路由策略 ID
     * @param targetBeanName 路由预测的目标 Bean 名称
     * @return 异常实例
     */
    public static RouteNotFoundException beanNotFound(String routerId, String targetBeanName) {
        return new RouteNotFoundException(
                BEAN_NOT_FOUND,
                routerId,
                String.format("Routing matched [%s], but no corresponding bean instance was found in the container", targetBeanName)
        );
    }

    /**
     * 获取路由 ID
     *
     * @return 路由 ID
     */
    public String getRouterId() {
        return routerId;
    }
}
