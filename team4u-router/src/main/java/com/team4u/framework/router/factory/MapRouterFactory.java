package com.team4u.framework.router.factory;

import com.team4u.framework.router.api.RoutePolicy;
import com.team4u.framework.router.api.Router;
import com.team4u.framework.router.engine.MapRouter;

/**
 * 映射路由器工厂
 */
public class MapRouterFactory implements RouterFactory {

    @Override
    public Router create(RoutePolicy policy) {
        return new MapRouter(policy);
    }

    @Override
    public String key() {
        return "map";
    }
}
