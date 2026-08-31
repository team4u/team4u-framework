package com.team4u.framework.flow;

import com.team4u.framework.policy.core.KeyedPolicyRegistry;

/**
 * 逻辑 AST 降级编译策略注册表。
 *
 * @author jay.wu
 */
final class LogicalLowererRegistry extends KeyedPolicyRegistry<Class<? extends Logical>, LogicalLowerer<?>> {

    private static final LogicalLowererRegistry GLOBAL = new LogicalLowererRegistry();

    static {
        GLOBAL.register(new LogicalLowerers.InvokeLowerer());
        GLOBAL.register(new LogicalLowerers.SequenceLowerer());
        GLOBAL.register(new LogicalLowerers.RouteLowerer());
        GLOBAL.register(new LogicalLowerers.FallbackLowerer());
        GLOBAL.register(new LogicalLowerers.ParallelLowerer());
        GLOBAL.register(new LogicalLowerers.AwaitLowerer());
        GLOBAL.register(new LogicalLowerers.ControlLowerer());
        GLOBAL.register(new LogicalLowerers.CompleteLowerer());
    }

    /**
     * 获取全局共享的降级策略注册表实例。
     */
    public static LogicalLowererRegistry global() {
        return GLOBAL;
    }

    @SuppressWarnings("unchecked")
    public LogicalLowererRegistry() {
        super(LogicalLowerer.class);
    }
}
