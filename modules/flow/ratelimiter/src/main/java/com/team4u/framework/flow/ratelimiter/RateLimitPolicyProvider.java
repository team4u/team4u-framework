package com.team4u.framework.flow.ratelimiter;

import com.team4u.framework.flow.definition.registry.PolicyBinding;
import com.team4u.framework.flow.definition.registry.PolicyDescriptor;
import com.team4u.framework.flow.definition.registry.PolicyProvider;

import java.util.Map;

/**
 * 流程限流策略 DSL 配置提供器（Rate Limit Policy Provider）。
 *
 * <p>根据 DSL 声明的策略 ID 与配置参数（如 {@code point}、{@code permits}、{@code action}），动态构造 {@link RateLimitPolicy} 实例与策略绑定。</p>
 *
 * @author jay.wu
 */
public class RateLimitPolicyProvider implements PolicyProvider {

    private final PolicyDescriptor descriptor;

    public RateLimitPolicyProvider(String id) {
        this.descriptor = PolicyDescriptor.builder()
                .id(id)
                .contract(RateLimitPolicy.class)
                .persistent(false)
                .build();
    }

    public RateLimitPolicyProvider() {
        this("rate-limit");
    }

    @Override
    public PolicyDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public PolicyBinding create(Map<String, Object> configuration) {
        String point = descriptor.id();
        Object pointVal = configuration.get("point");
        if (pointVal instanceof String) {
            point = (String) pointVal;
        }

        Integer permits = 1;
        Object permitsVal = configuration.get("permits");
        if (permitsVal instanceof Number) {
            permits = ((Number) permitsVal).intValue();
        } else if (permitsVal instanceof String) {
            try {
                permits = Integer.parseInt((String) permitsVal);
            } catch (NumberFormatException ignored) { }
        }

        RateLimitAction action = RateLimitAction.FAIL;
        Object actionVal = configuration.get("action");
        if (actionVal instanceof String) {
            String actStr = ((String) actionVal).toUpperCase();
            if ("REJECT".equals(actStr)) {
                action = RateLimitAction.REJECT;
            } else if ("FAIL".equals(actStr)) {
                action = RateLimitAction.FAIL;
            }
        }

        RateLimitPolicy<Object> policy = RateLimitPolicy.<Object>builder()
                .point(point)
                .permits(permits)
                .action(action)
                .build();

        return PolicyBinding.builder()
                .instance(policy)
                .persistent(false)
                .build();
    }
}
