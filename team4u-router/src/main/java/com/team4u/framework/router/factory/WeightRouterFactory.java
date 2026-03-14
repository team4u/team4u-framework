package com.team4u.framework.router.factory;

import com.team4u.framework.router.api.Router;
import com.team4u.framework.router.api.RouterType;
import com.team4u.framework.router.api.model.RoutePolicy;
import com.team4u.framework.router.core.WeightRouter;
import com.team4u.framework.router.spi.RouterFactory;

/**
 * 权重路由器工厂 (Weight Router Factory)
 * <p>
 * 专门用于创建 {@link com.team4u.framework.router.core.WeightRouter} 实例，实现基于哈希区间的流量均衡分发。
 * </p>
 *
 * @author jay.wu
 */
public class WeightRouterFactory implements RouterFactory {

    @Override
    public Router create(RoutePolicy policy) {
        return new WeightRouter(policy);
    }

    @Override
    public String key() {
        return RouterType.WEIGHT;
    }
}
