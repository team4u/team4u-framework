package com.team4u.framework.flow;

import com.team4u.framework.policy.core.KeyedPolicyRegistry;

/**
 * 物理执行计划节点投影策略注册表。
 *
 * @author jay.wu
 */
final class PlanNodeProjectorRegistry extends KeyedPolicyRegistry<Class<? extends PlanNode>, PlanNodeProjector<?>> {

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
