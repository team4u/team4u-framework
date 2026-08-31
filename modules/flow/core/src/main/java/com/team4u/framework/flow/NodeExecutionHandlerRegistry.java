package com.team4u.framework.flow;

import com.team4u.framework.policy.core.KeyedPolicyRegistry;

/**
 * 运行时物理节点推进执行策略注册表。
 *
 * @author jay.wu
 */
final class NodeExecutionHandlerRegistry extends KeyedPolicyRegistry<Class<? extends PlanNode>, NodeExecutionHandler<?>> {

    private static final NodeExecutionHandlerRegistry GLOBAL = new NodeExecutionHandlerRegistry();

    static {
        GLOBAL.register(new NodeExecutionHandlers.InvokeExecutionHandler());
        GLOBAL.register(new NodeExecutionHandlers.SequenceExecutionHandler());
        GLOBAL.register(new NodeExecutionHandlers.RouteExecutionHandler());
        GLOBAL.register(new NodeExecutionHandlers.FallbackExecutionHandler());
        GLOBAL.register(new NodeExecutionHandlers.ParallelExecutionHandler());
        GLOBAL.register(new NodeExecutionHandlers.AwaitExecutionHandler());
        GLOBAL.register(new NodeExecutionHandlers.ControlExecutionHandler());
        GLOBAL.register(new NodeExecutionHandlers.CompleteExecutionHandler());
    }

    /**
     * 获取全局共享的节点执行策略注册表。
     */
    public static NodeExecutionHandlerRegistry global() {
        return GLOBAL;
    }

    @SuppressWarnings("unchecked")
    public NodeExecutionHandlerRegistry() {
        super(NodeExecutionHandler.class);
    }
}
