package com.team4u.framework.flow.durable;

import com.team4u.framework.policy.core.KeyedPolicyRegistry;

/**
 * Durable 运行时父帧结果归约策略注册表。
 *
 * @author jay.wu
 */
final class DurableFrameReducePolicyRegistry extends KeyedPolicyRegistry<Class<? extends DurablePlanNode>, DurableFrameReducePolicy<?>> {

    private static final DurableFrameReducePolicyRegistry GLOBAL = new DurableFrameReducePolicyRegistry();

    static {
        GLOBAL.register(new DurableFrameReducePolicies.SequenceReducePolicy());
        GLOBAL.register(new DurableFrameReducePolicies.RouteReducePolicy());
        GLOBAL.register(new DurableFrameReducePolicies.FallbackReducePolicy());
        GLOBAL.register(new DurableFrameReducePolicies.ControlReducePolicy());
    }

    /**
     * 获取全局共享的 Durable 帧归约策略注册表。
     */
    public static DurableFrameReducePolicyRegistry global() {
        return GLOBAL;
    }

    @SuppressWarnings("unchecked")
    public DurableFrameReducePolicyRegistry() {
        super(DurableFrameReducePolicy.class);
    }
}
