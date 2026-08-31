package com.team4u.framework.flow;

import com.team4u.framework.policy.core.KeyedPolicyRegistry;

/**
 * 运行时父帧结果归约策略注册表。
 *
 * @author jay.wu
 */
final class FrameReducePolicyRegistry extends KeyedPolicyRegistry<Class<? extends PlanNode>, FrameReducePolicy<?>> {

    private static final FrameReducePolicyRegistry GLOBAL = new FrameReducePolicyRegistry();

    static {
        GLOBAL.register(new FrameReducePolicies.SequenceReducePolicy());
        GLOBAL.register(new FrameReducePolicies.RouteReducePolicy());
        GLOBAL.register(new FrameReducePolicies.FallbackReducePolicy());
        GLOBAL.register(new FrameReducePolicies.ControlReducePolicy());
    }

    /**
     * 获取全局共享的帧归约策略注册表。
     */
    public static FrameReducePolicyRegistry global() {
        return GLOBAL;
    }

    @SuppressWarnings("unchecked")
    public FrameReducePolicyRegistry() {
        super(FrameReducePolicy.class);
    }
}
