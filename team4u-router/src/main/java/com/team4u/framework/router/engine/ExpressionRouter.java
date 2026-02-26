package com.team4u.framework.router.engine;

import com.team4u.framework.criterion.Criteria;
import com.team4u.framework.criterion.MatchContext;
import com.team4u.framework.router.api.RoutePolicy;
import com.team4u.framework.router.api.RouteResult;
import com.team4u.framework.router.api.Router;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 表达式路由器
 */
public class ExpressionRouter implements Router {

    private final Map<String, Object> rules;
    private final Criteria criteria;

    public ExpressionRouter(RoutePolicy policy) {
        this(policy, null);
    }

    public ExpressionRouter(RoutePolicy policy, Criteria criteria) {
        this.rules = policy.getRules();
        this.criteria = criteria != null ? criteria : Criteria.global();
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> RouteResult<T> route(Object request) {
        MatchContext context = (request instanceof MatchContext) ?
                (MatchContext) request : MatchContext.of(request);

        for (Map.Entry<String, Object> entry : rules.entrySet()) {
            String expr = entry.getKey();
            if ("*".equals(expr) || criteria.matches(expr, context)) {
                return RouteResult.matched((T) entry.getValue());
            }
        }

        return RouteResult.unmatch();
    }
}
