package com.team4u.framework.router.core;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import com.team4u.framework.router.api.RouterType;
import com.team4u.framework.router.api.exception.RouteConfigException;
import com.team4u.framework.router.api.model.RoutePolicy;
import com.team4u.framework.router.api.model.RouteResult;
import com.team4u.framework.router.api.model.RouteRule;
import com.team4u.framework.router.api.trace.RouteTrace;
import com.team4u.framework.router.api.trace.RuleTrace;

import java.util.HashMap;
import java.util.Map;

/**
 * 映射路由器
 */
public class MapRouter extends AbstractRouter {

    private static final Log log = LogFactory.get();

    private final Map<String, Object> rules;

    public MapRouter(RoutePolicy policy) {
        super(policy);
        this.rules = initializeRules(policy);
    }

    /**
     * 初始化路由规则映射表
     *
     * @param policy 路由策略配置
     * @return 精确匹配的规则映射表
     * @throws RouteConfigException 当配置中存在重复的匹配条件 (Condition) 时抛出，以防止业务逻辑冲突
     */
    private Map<String, Object> initializeRules(RoutePolicy policy) {
        Map<String, Object> ruleMap = new HashMap<>();

        if (policy.getRules() != null) {
            for (RouteRule rule : policy.getRules()) {
                // 跳过匹配条件为空的规则
                if (rule.getCondition() == null) {
                    continue;
                }

                String key = rule.getCondition();
                Object value = rule.getValue();

                // 拦截重复 Key，抛出附带 Policy ID 和具体冲突值的精确异常，提升排障效率
                if (ruleMap.containsKey(key)) {
                    throw RouteConfigException.duplicateCondition(policy.getId(), key, ruleMap.get(key), value);
                }
                ruleMap.put(key, value);
            }
        }

        return ruleMap;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> RouteResult<T> route(Object request) {
        // 请求对象为空时直接走兜底逻辑
        if (request == null) {
            return fallback();
        }

        // 尝试从规则库中精确匹配
        String routingKey = String.valueOf(request);

        // 使用 containsKey 判断 key 是否存在，正确处理 value 为 null 的情况
        if (rules.containsKey(routingKey)) {
            Object target = rules.get(routingKey);
            // 记录匹配命中的日志 (仅在 TRACE 级别)
            if (log.isTraceEnabled()) {
                log.trace("Route matched: key [{}] -> value [{}]", routingKey, target);
            }
            return RouteResult.matched((T) target, routingKey);
        }

        // 未匹配时记录日志 (仅在 TRACE 级别)
        if (log.isTraceEnabled()) {
            log.trace("Route unmatch: key [{}] not found, using fallback [{}]", routingKey, fallbackValue);
        }

        // 未匹配时走兜底逻辑
        return fallback();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> RouteTrace<T> trace(Object request) {
        long start = System.currentTimeMillis();
        RouteTrace<T> routeTrace = createTrace(RouterType.MAP);

        if (request == null) {
            routeTrace.setResult(fallback());
            return completeTrace(routeTrace, start);
        }

        String routingKey = String.valueOf(request);

        // 使用 containsKey 判断 key 是否存在，正确处理 value 为 null 的情况
        if (rules.containsKey(routingKey)) {
            Object target = rules.get(routingKey);
            routeTrace.addStep(RuleTrace.normal(routingKey, true, null));
            routeTrace.setResult(RouteResult.matched((T) target, routingKey));
        } else {
            routeTrace.addStep(RuleTrace.normal(routingKey, false, null));
            routeTrace.addStep(RuleTrace.fallback(fallbackValue != null));
            routeTrace.setResult(fallback());
        }

        return completeTrace(routeTrace, start);
    }
}
