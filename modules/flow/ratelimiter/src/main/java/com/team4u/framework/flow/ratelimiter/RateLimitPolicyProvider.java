package com.team4u.framework.flow.ratelimiter;

import com.team4u.framework.flow.definition.registry.PolicyBinding;
import com.team4u.framework.flow.definition.registry.PolicyDescriptor;
import com.team4u.framework.flow.definition.registry.PolicyProvider;
import com.team4u.framework.flow.definition.util.ConfigMapReader;

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
        ConfigMapReader reader = ConfigMapReader.of(configuration);
        String point = reader.getString("point", descriptor.id());
        Integer permits = reader.getInt("permits", 1);
        RateLimitAction action = reader.getEnum(RateLimitAction.class, "action", RateLimitAction.FAIL);

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
