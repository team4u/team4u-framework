package com.team4u.framework.router.factory;

import com.team4u.framework.router.api.Router;
import com.team4u.framework.router.api.RouterType;
import com.team4u.framework.router.api.model.RoutePolicy;
import com.team4u.framework.router.core.MapRouter;
import com.team4u.framework.router.spi.RouterFactory;

/**
 * 映射路由器工厂 (Map Router Factory)
 * <p>
 * 专门用于创建 {@link com.team4u.framework.router.core.MapRouter} 实例，实现基于键值的精确分发逻辑。
 * </p>
 */
public class MapRouterFactory implements RouterFactory {

    @Override
    public Router create(RoutePolicy policy) {
        return new MapRouter(policy);
    }

    @Override
    public String key() {
        return RouterType.MAP;
    }
}
