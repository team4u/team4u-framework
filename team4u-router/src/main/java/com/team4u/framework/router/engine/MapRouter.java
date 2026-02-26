package com.team4u.framework.router.engine;

import com.team4u.framework.router.api.RoutePolicy;
import com.team4u.framework.router.api.RouteResult;
import com.team4u.framework.router.api.Router;

import java.util.Map;

/**
 * 映射路由器
 */
public class MapRouter implements Router {

    private final Map<String, Object> rules;

    public MapRouter(RoutePolicy policy) {
        this.rules = policy.getRules();
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> RouteResult<T> route(Object request) {
        if (request == null) {
            return fallback();
        }

        Object target = rules.get(String.valueOf(request));
        if (target != null) {
            return RouteResult.matched((T) target);
        }

        return fallback();
    }

    @SuppressWarnings("unchecked")
    private <T> RouteResult<T> fallback() {
        Object target = rules.get("*");
        return target != null ? RouteResult.matched((T) target) : RouteResult.unmatch();
    }
}
