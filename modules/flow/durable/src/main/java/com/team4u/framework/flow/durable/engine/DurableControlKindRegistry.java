package com.team4u.framework.flow.durable.engine;

import com.team4u.framework.flow.spi.ControlKind;
import com.team4u.framework.policy.core.KeyedPolicyRegistry;

/**
 * Durable 治理控制类型策略注册表。
 *
 * @author jay.wu
 */
public final class DurableControlKindRegistry extends KeyedPolicyRegistry<ControlKind, DurableControlKindHandler> {

    private static final DurableControlKindRegistry GLOBAL = new DurableControlKindRegistry();

    static {
        GLOBAL.register(new DurableControlKindHandlers.TimeoutHandler());
        GLOBAL.register(new DurableControlKindHandlers.RetryHandler());
        GLOBAL.register(new DurableControlKindHandlers.PolicyHandler());
        GLOBAL.register(new DurableControlKindHandlers.PersistentPolicyHandler());
    }

    /**
     * 获取全局共享的 Durable 控制类型策略注册表。
     */
    public static DurableControlKindRegistry global() {
        return GLOBAL;
    }

    public DurableControlKindRegistry() {
        super(DurableControlKindHandler.class);
    }
}
