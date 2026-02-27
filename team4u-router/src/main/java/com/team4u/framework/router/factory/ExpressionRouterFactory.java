package com.team4u.framework.router.factory;

import com.team4u.framework.criterion.Criteria;
import com.team4u.framework.router.api.model.RoutePolicy;
import com.team4u.framework.router.api.Router;
import com.team4u.framework.router.core.ExpressionRouter;
import com.team4u.framework.router.spi.RouterFactory;

/**
 * 表达式路由器工厂
 */
public class ExpressionRouterFactory implements RouterFactory {

    private final Criteria criteria;

    public ExpressionRouterFactory() {
        this(null);
    }

    public ExpressionRouterFactory(Criteria criteria) {
        this.criteria = criteria != null ? criteria : Criteria.global();
    }

    @Override
    public Router create(RoutePolicy policy) {
        return new ExpressionRouter(policy, criteria);
    }

    @Override
    public String key() {
        return "expression";
    }
}
