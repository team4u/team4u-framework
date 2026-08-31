package com.team4u.framework.flow;

import com.team4u.framework.policy.core.KeyedPolicyRegistry;

/**
 * 逻辑 AST 结构描述生成策略注册表。
 *
 * @author jay.wu
 */
final class LogicalDescriberRegistry extends KeyedPolicyRegistry<Class<? extends Logical>, LogicalDescriber<?>> {

    private static final LogicalDescriberRegistry GLOBAL = new LogicalDescriberRegistry();

    static {
        GLOBAL.register(new LogicalDescribers.InvokeDescriber());
        GLOBAL.register(new LogicalDescribers.SequenceDescriber());
        GLOBAL.register(new LogicalDescribers.RouteDescriber());
        GLOBAL.register(new LogicalDescribers.FallbackDescriber());
        GLOBAL.register(new LogicalDescribers.ParallelDescriber());
        GLOBAL.register(new LogicalDescribers.AwaitDescriber());
        GLOBAL.register(new LogicalDescribers.ControlDescriber());
        GLOBAL.register(new LogicalDescribers.CompleteDescriber());
    }

    /**
     * 获取全局共享的描述生成策略注册表。
     */
    public static LogicalDescriberRegistry global() {
        return GLOBAL;
    }

    @SuppressWarnings("unchecked")
    public LogicalDescriberRegistry() {
        super(LogicalDescriber.class);
    }
}
