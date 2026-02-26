package com.team4u.framework.router.factory;

import com.team4u.framework.router.api.RoutePolicy;
import com.team4u.framework.router.api.Router;
import com.team4u.framework.router.engine.ExpressionRouter;

/**
 * 表达式路由器工厂
 */
public class ExpressionRouterFactory implements RouterFactory {

    @Override
    public Router create(RoutePolicy policy) {
        return new ExpressionRouter(policy);
    }

    @Override
    public String key() {
        return "expression";
    }
}
