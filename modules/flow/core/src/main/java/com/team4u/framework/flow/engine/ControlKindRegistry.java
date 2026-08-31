package com.team4u.framework.flow.engine;

import com.team4u.framework.policy.core.KeyedPolicyRegistry;
import com.team4u.framework.flow.compiler.PlanNode;

/**
 * 治理控制类型策略注册表。
 *
 * @author jay.wu
 */
public final class ControlKindRegistry extends KeyedPolicyRegistry<PlanNode.Control.Kind, ControlKindHandler> {

    private static final ControlKindRegistry GLOBAL = new ControlKindRegistry();

    static {
        GLOBAL.register(new ControlKindHandlers.TimeoutHandler());
        GLOBAL.register(new ControlKindHandlers.RetryHandler());
        GLOBAL.register(new ControlKindHandlers.PolicyHandler());
        GLOBAL.register(new ControlKindHandlers.PersistentPolicyHandler());
    }

    /**
     * 获取全局共享的控制类型策略注册表。
     */
    public static ControlKindRegistry global() {
        return GLOBAL;
    }

    public ControlKindRegistry() {
        super(ControlKindHandler.class);
    }
}
