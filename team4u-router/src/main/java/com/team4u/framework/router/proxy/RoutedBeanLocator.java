package com.team4u.framework.router.proxy;

import cn.hutool.core.util.StrUtil;
import com.team4u.framework.bean.BeanManager;
import com.team4u.framework.router.RoutingManager;
import com.team4u.framework.router.api.model.RouteResult;

/**
 * 路由 Bean 定位器
 * <p>
 * 将路由引擎的结果直接解析为可执行的 Bean 实例。
 * 用于在业务代码中快速通过路由规则获取特定的实现类。
 * </p>
 *
 * @author jay.wu
 */
public class RoutedBeanLocator {

    /**
     * 根据路由规则和上下文，动态获取对应的 Bean 实例
     *
     * @param routerId     路由策略 ID (对应配置中心的 router.{routerId})
     * @param routeContext 路由上下文 (参与条件计算的请求对象)
     * @param expectedType 期望返回的 Bean 接口类型
     * @param <T>          期望的类型
     * @return 匹配的 Bean 实例
     * @throws IllegalStateException 当路由未命中或 Bean 不存在时抛出
     */
    public static <T> T locate(String routerId, Object routeContext, Class<T> expectedType) {
        return locate(RoutingManager.global(), routerId, routeContext, expectedType);
    }

    /**
     * 根据自定义路由管理器、路由规则和上下文，动态获取对应的 Bean 实例
     *
     * @param routingManager 自定义路由管理器
     * @param routerId       路由策略 ID (对应配置中心的 router.{routerId})
     * @param routeContext   路由上下文 (参与条件计算的请求对象)
     * @param expectedType   期望返回的 Bean 接口类型
     * @param <T>            期望的类型
     * @return 匹配的 Bean 实例
     * @throws IllegalStateException 当路由未命中或 Bean 不存在时抛出
     */
    @SuppressWarnings("unchecked")
    public static <T> T locate(RoutingManager routingManager, String routerId, Object routeContext,
                               Class<T> expectedType) {
        // 1. 执行路由计算，期望策略中配置的 value 是目标 Bean 的名称
        RouteResult<String> result = routingManager.route(routerId, routeContext, String.class);

        // 如果未命中且没有兜底，抛出异常阻断
        if (result == null || result.isNotMatch() || StrUtil.isBlank(result.getValue())) {
            throw new IllegalStateException(String.format(
                    "Routing failed: No matching rule or fallback configuration found for router ID [%s]", routerId));
        }

        String targetBeanName = result.getValue();

        // 2. 从统一的 BeanManager 中获取真实的执行实例
        Object bean = BeanManager.getInstance().getBean(targetBeanName);

        if (bean == null) {
            throw new IllegalStateException(String.format(
                    "Routing matched [%s], but no corresponding bean instance was found in the container",
                    targetBeanName));
        }

        // 3. 类型安全校验
        if (!expectedType.isInstance(bean)) {
            throw new ClassCastException(String.format(
                    "The routed bean [%s] is of type [%s], but expected type is [%s]",
                    targetBeanName, bean.getClass().getName(), expectedType.getName()));
        }

        return (T) bean;
    }
}
