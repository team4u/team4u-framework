package com.team4u.framework.flow.compiler;

/**
 * 逻辑 AST 降级编译策略注册表。
 *
 * <p>静态初始化注册完毕后自动冻结（只读），写入操作抛出
 * {@link UnsupportedOperationException}；自定义扩展点请在冻结前通过自建实例注册，
 * 全局实例仅提供读取。</p>
 *
 * @author jay.wu
 */
public final class LogicalLowererRegistry extends FrozenKeyedPolicyRegistry<Class<? extends Logical>, LogicalLowerer<?>> {

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
        GLOBAL.freeze();
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
