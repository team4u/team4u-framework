package com.team4u.framework.router.core;

import cn.hutool.core.util.NumberUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import com.team4u.framework.router.api.model.RoutePolicy;
import com.team4u.framework.router.api.model.RouteResult;
import com.team4u.framework.router.api.model.RouteRule;
import com.team4u.framework.router.api.trace.RouteTrace;
import com.team4u.framework.router.api.trace.RuleTrace;

import java.util.Map;
import java.util.TreeMap;

/**
 * 权重路由器
 * <p>
 * 根据配置的权重比例进行流量分发，自动处理权重累加逻辑。
 *
 * @author jay.wu
 */
public class WeightRouter extends AbstractRouter {

    private static final Log log = LogFactory.get();

    // 使用 TreeMap 存储累加权重和目标值的映射，利用其 ceilingEntry 快速定位区间
    private final TreeMap<Integer, Object> weightMap = new TreeMap<>();
    private final Object fallbackValue;
    private int totalWeight = 0;

    public WeightRouter(RoutePolicy policy) {
        this.fallbackValue = policy.getFallbackValue();
        this.initializeRules(policy);
    }

    /**
     * 初始化路由规则
     *
     * @param policy 路由策略
     */
    private void initializeRules(RoutePolicy policy) {
        if (policy.getRules() == null) {
            return;
        }

        // 初始化时，自动完成权重的累加逻辑
        for (RouteRule rule : policy.getRules()) {
            if (!NumberUtil.isInteger(rule.getCondition())) {
                throw new IllegalArgumentException(
                        "WeightRouter condition must be an integer, but got: " + rule.getCondition());
            }
            int weight = Integer.parseInt(rule.getCondition());
            if (weight > 0) {
                totalWeight += weight;
                // 将累加后的总权重作为 Key
                weightMap.put(totalWeight, rule.getValue());
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> RouteResult<T> route(Object request) {
        if (totalWeight == 0 || request == null) {
            return fallback();
        }

        // 1. 获取需要 Hash 的路由因子（通常是 userId 或 deviceId）
        String routingKey = String.valueOf(request);

        // 2. 将路由因子转换为一个 [0, totalWeight) 范围内的整数
        // 注意：Math.abs 遇到 Integer.MIN_VALUE 会溢出变负数，采用按位与处理
        int hashValue = (routingKey.hashCode() & Integer.MAX_VALUE) % totalWeight;

        // 3. 利用 TreeMap 的特性，寻找大于 hashValue 的最小 Key
        // 例如：规则是 20, 30。Map 中存的是 {20: A, 50: B}
        // 如果 hashValue 是 15 -> 命中 20 (A)
        // 如果 hashValue 是 25 -> 命中 50 (B)
        Map.Entry<Integer, Object> entry = weightMap.ceilingEntry(hashValue + 1);

        if (entry != null) {
            if (log.isTraceEnabled()) {
                log.trace("Route matched: key [{}] (hash: {}) -> value [{}]", routingKey, hashValue, entry.getValue());
            }
            return RouteResult.matched((T) entry.getValue(), String.valueOf(entry.getKey()));
        }

        return fallback();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> RouteTrace<T> trace(Object request) {
        long start = System.currentTimeMillis();
        RouteTrace<T> routeTrace = new RouteTrace<>();
        routeTrace.setRouterType("weight");

        if (totalWeight == 0 || request == null) {
            routeTrace.setResult(fallback());
            routeTrace.setCostMs(System.currentTimeMillis() - start);
            return routeTrace;
        }

        String routingKey = String.valueOf(request);
        int hashValue = (routingKey.hashCode() & Integer.MAX_VALUE) % totalWeight;
        Map.Entry<Integer, Object> entry = weightMap.ceilingEntry(hashValue + 1);

        if (entry != null) {
            routeTrace.addStep(RuleTrace.normal("weight_hash:" + hashValue, true, entry.getKey()));
            routeTrace.setResult(RouteResult.matched((T) entry.getValue(), String.valueOf(entry.getKey())));
        } else {
            routeTrace.addStep(RuleTrace.normal("weight_hash:" + hashValue, false, null));
            routeTrace.addStep(RuleTrace.fallback(fallbackValue != null));
            routeTrace.setResult(fallback());
        }

        routeTrace.setCostMs(System.currentTimeMillis() - start);
        return routeTrace;
    }

    /**
     * 执行兜底逻辑
     */
    @SuppressWarnings("unchecked")
    private <T> RouteResult<T> fallback() {
        return fallbackValue != null ? RouteResult.matched((T) fallbackValue) : RouteResult.unmatch();
    }
}
