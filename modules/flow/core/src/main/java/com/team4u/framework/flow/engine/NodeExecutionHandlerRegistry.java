package com.team4u.framework.flow.engine;

import com.team4u.framework.flow.compiler.FrozenKeyedPolicyRegistry;
import com.team4u.framework.flow.compiler.PlanNode;

/**
 * 运行时物理节点推进执行策略注册表。
 *
 * <p>静态初始化注册完毕后自动冻结（只读），写入操作抛出
 * {@link UnsupportedOperationException}；自定义扩展点请在冻结前通过自建实例注册，
 * 全局实例仅提供读取。</p>
 *
 * @author jay.wu
 */
public final class NodeExecutionHandlerRegistry extends FrozenKeyedPolicyRegistry<Class<? extends PlanNode>, NodeExecutionHandler<?>> {

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
        GLOBAL.register(new NodeExecutionHandlers.AdapterExecutionHandler());
        GLOBAL.freeze();
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
