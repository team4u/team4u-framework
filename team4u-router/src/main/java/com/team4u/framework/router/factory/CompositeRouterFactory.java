package com.team4u.framework.router.factory;

import com.team4u.framework.router.RoutingManager;
import com.team4u.framework.router.api.Router;
import com.team4u.framework.router.api.RouterType;
import com.team4u.framework.router.api.model.RoutePolicy;
import com.team4u.framework.router.core.CompositeRouter;
import com.team4u.framework.router.spi.RouterFactory;

/**
 * 复合路由器构建工厂
 */
public class CompositeRouterFactory implements RouterFactory {

    private final RoutingManager manager;

    /**
     * 带管理器的构造，供 Builder 显式注册使用
     */
    public CompositeRouterFactory(RoutingManager manager) {
        this.manager = manager;
    }

    @Override
    public String key() {
        return RouterType.COMPOSITE;
    }

    @Override
    public Router create(RoutePolicy policy) {
        // 使用注入的 manager
        return new CompositeRouter(policy, manager != null ? manager : RoutingManager.global());
    }
}
