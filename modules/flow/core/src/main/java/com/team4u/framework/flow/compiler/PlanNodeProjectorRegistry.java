package com.team4u.framework.flow.compiler;

/**
 * 物理执行计划节点投影策略注册表。
 *
 * <p>静态初始化注册完毕后自动冻结（只读），写入操作抛出
 * {@link UnsupportedOperationException}；自定义扩展点请在冻结前通过自建实例注册，
 * 全局实例仅提供读取。</p>
 *
 * @author jay.wu
 */
public final class PlanNodeProjectorRegistry extends FrozenKeyedPolicyRegistry<Class<? extends PlanNode>, PlanNodeProjector<?>> {

    private static final PlanNodeProjectorRegistry GLOBAL = new PlanNodeProjectorRegistry();

    static {
        GLOBAL.register(new PlanNodeProjectors.InvokeProjector());
        GLOBAL.register(new PlanNodeProjectors.SequenceProjector());
        GLOBAL.register(new PlanNodeProjectors.RouteProjector());
        GLOBAL.register(new PlanNodeProjectors.FallbackProjector());
        GLOBAL.register(new PlanNodeProjectors.ParallelProjector());
        GLOBAL.register(new PlanNodeProjectors.AwaitProjector());
        GLOBAL.register(new PlanNodeProjectors.ControlProjector());
        GLOBAL.register(new PlanNodeProjectors.CompleteProjector());
        GLOBAL.register(new PlanNodeProjectors.AdapterProjector());
        GLOBAL.freeze();
    }

    /**
     * 获取全局共享的投影策略注册表。
     */
    public static PlanNodeProjectorRegistry global() {
        return GLOBAL;
    }

    @SuppressWarnings("unchecked")
    public PlanNodeProjectorRegistry() {
        super(PlanNodeProjector.class);
    }
}
