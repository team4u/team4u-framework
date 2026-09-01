package com.team4u.framework.flow.ratelimiter;

import com.team4u.framework.flow.definition.registry.FlowDefinitionExtension;
import com.team4u.framework.flow.definition.registry.FlowDefinitionRegistry;

/**
 * 限流治理模块在 FlowDefinitionRegistry 中的自动扩展 SPI 实现。
 *
 * @author jay.wu
 */
public class RateLimitFlowDefinitionExtension implements FlowDefinitionExtension {

    @Override
    public void contribute(FlowDefinitionRegistry.Builder builder) {
        builder.policyProvider(new RateLimitPolicyProvider("rate-limit"));
        builder.policyProvider(new RateLimitPolicyProvider("ratelimit"));
        builder.policyProvider(new RateLimitPolicyProvider("flow.rate-limit"));
    }
}
