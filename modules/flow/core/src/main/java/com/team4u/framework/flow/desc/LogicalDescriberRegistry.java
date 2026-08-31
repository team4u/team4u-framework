package com.team4u.framework.flow.desc;

import com.team4u.framework.flow.compiler.FrozenKeyedPolicyRegistry;
import com.team4u.framework.flow.compiler.Logical;

/**
 * 逻辑 AST 结构描述生成策略注册表。
 *
 * <p>静态初始化注册完毕后自动冻结（只读），写入操作抛出
 * {@link UnsupportedOperationException}；自定义扩展点请在冻结前通过自建实例注册，
 * 全局实例仅提供读取。</p>
 *
 * @author jay.wu
 */
public final class LogicalDescriberRegistry extends FrozenKeyedPolicyRegistry<Class<? extends Logical>, LogicalDescriber<?>> {

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
        GLOBAL.freeze();
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
