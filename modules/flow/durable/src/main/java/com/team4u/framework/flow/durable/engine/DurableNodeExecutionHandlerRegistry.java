package com.team4u.framework.flow.durable.engine;

import com.team4u.framework.policy.core.KeyedPolicyRegistry;

/**
 * Durable 运行时物理节点推进执行策略注册表。
 *
 * @author jay.wu
 */
public final class DurableNodeExecutionHandlerRegistry extends KeyedPolicyRegistry<Class<? extends DurablePlanNode>, DurableNodeExecutionHandler<?>> {

    private static final DurableNodeExecutionHandlerRegistry GLOBAL = new DurableNodeExecutionHandlerRegistry();

    static {
        GLOBAL.register(new DurableNodeExecutionHandlers.InvokeExecutionHandler());
        GLOBAL.register(new DurableNodeExecutionHandlers.SequenceExecutionHandler());
        GLOBAL.register(new DurableNodeExecutionHandlers.RouteExecutionHandler());
        GLOBAL.register(new DurableNodeExecutionHandlers.FallbackExecutionHandler());
        GLOBAL.register(new DurableNodeExecutionHandlers.ParallelExecutionHandler());
        GLOBAL.register(new DurableNodeExecutionHandlers.AwaitExecutionHandler());
        GLOBAL.register(new DurableNodeExecutionHandlers.ControlExecutionHandler());
        GLOBAL.register(new DurableNodeExecutionHandlers.CompleteExecutionHandler());
    }

    /**
     * 获取全局共享的节点执行策略注册表。
     */
    public static DurableNodeExecutionHandlerRegistry global() {
        return GLOBAL;
    }

    @SuppressWarnings("unchecked")
    public DurableNodeExecutionHandlerRegistry() {
        super(DurableNodeExecutionHandler.class);
    }
}
