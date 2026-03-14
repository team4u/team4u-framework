package com.team4u.framework.router.spi;

import com.team4u.framework.policy.api.KeyedPolicy;
import com.team4u.framework.router.api.Router;
import com.team4u.framework.router.api.model.RoutePolicy;

/**
 * 路由器创建工厂接口 (SPI)
 * <p>
 * 这是框架的扩展点之一。开发者可以通过实现此接口并注册到 {@link com.team4u.framework.router.factory.RouterFactoryRegistry} 中，
 * 来定义特定类型的路由逻辑（如：自定义的算法路由器、基于外部系统的动态路由器等）。
 * </p>
 */
public interface RouterFactory extends KeyedPolicy<String> {

    /**
     * 创建路由器
     *
     * @param policy 路由配置
     * @return 路由器实例
     */
    Router create(RoutePolicy policy);
}
