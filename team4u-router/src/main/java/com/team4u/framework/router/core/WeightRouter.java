package com.team4u.framework.router.core;

import com.team4u.framework.base.util.HashUtil;
import com.team4u.framework.base.util.NumberUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.team4u.framework.router.api.RouterType;
import com.team4u.framework.router.api.exception.RouteConfigException;
import com.team4u.framework.router.api.model.RoutePolicy;
import com.team4u.framework.router.api.model.RouteResult;
import com.team4u.framework.router.api.model.RouteRule;
import com.team4u.framework.router.api.trace.RouteTrace;
import com.team4u.framework.router.api.trace.RuleTrace;

import java.nio.charset.StandardCharsets;
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

    private static final Logger log = LoggerFactory.getLogger(WeightRouter.class);

    // 使用 TreeMap 存储累加权重和目标值的映射，利用其 ceilingEntry 快速定位区间
    private final TreeMap<Integer, Object> weightMap = new TreeMap<>();
    private final TreeMap<Integer, String> conditionMap = new TreeMap<>();
    private int totalWeight = 0;

    public WeightRouter(RoutePolicy policy) {
        super(policy);
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
                throw RouteConfigException.validationError(
                        policy.getId(),
                        "WeightRouter condition must be an integer, but got: " + rule.getCondition());
            }
            int weight = Integer.parseInt(rule.getCondition());
            if (weight < 0) {
                throw RouteConfigException.validationError(
                        policy.getId(),
                        "WeightRouter weight must be >= 0, but got: " + weight);
            }
            if (weight > 0) {
                totalWeight += weight;
                // 将累加后的总权重作为 Key
                weightMap.put(totalWeight, rule.getValue());
                conditionMap.put(totalWeight, rule.getCondition());
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
        // 使用 MurmurHash 算法替代原生 hashCode 以保证流量分布更加均匀，避免哈希碰撞
        int hashValue = (HashUtil.murmur32(routingKey.getBytes(StandardCharsets.UTF_8)) & Integer.MAX_VALUE) % totalWeight;

        // 3. 利用 TreeMap 的特性，寻找大于 hashValue 的最小 Key
        // 例如：规则是 20, 30。Map 中存的是 {20: A, 50: B}
        // 如果 hashValue 是 15 -> 命中 20 (A)
        // 如果 hashValue 是 25 -> 命中 50 (B)
        Map.Entry<Integer, Object> entry = weightMap.ceilingEntry(hashValue + 1);

        if (entry != null) {
            String condition = conditionMap.get(entry.getKey());
            if (log.isTraceEnabled()) {
                log.trace("Route matched: key [{}] (hash: {}) -> value [{}]", routingKey, hashValue, entry.getValue());
            }
            return RouteResult.ruleMatch((T) entry.getValue(), condition);
        }

        return fallback();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> RouteTrace<T> trace(Object request) {
        long start = System.currentTimeMillis();
        RouteTrace<T> routeTrace = createTrace(RouterType.WEIGHT);

        if (totalWeight == 0 || request == null) {
            routeTrace.setResult(fallback());
            return completeTrace(routeTrace, start);
        }

        String routingKey = String.valueOf(request);
        int hashValue = (HashUtil.murmur32(routingKey.getBytes(StandardCharsets.UTF_8)) & Integer.MAX_VALUE) % totalWeight;
        Map.Entry<Integer, Object> entry = weightMap.ceilingEntry(hashValue + 1);

        if (entry != null) {
            String condition = conditionMap.get(entry.getKey());
            Integer lowerKey = weightMap.lowerKey(entry.getKey());
            int lowerBound = lowerKey != null ? lowerKey : 0;
            routeTrace.addStep(RuleTrace.normal(
                    condition,
                    true,
                    "hash=" + hashValue + ", range=[" + lowerBound + "," + entry.getKey() + ")"));
            routeTrace.setResult(RouteResult.ruleMatch((T) entry.getValue(), condition));
        } else {
            routeTrace.addStep(RuleTrace.normal(String.valueOf(hashValue), false, null));
            routeTrace.addStep(RuleTrace.fallback(fallbackValue != null));
            routeTrace.setResult(fallback());
        }

        return completeTrace(routeTrace, start);
    }
}
