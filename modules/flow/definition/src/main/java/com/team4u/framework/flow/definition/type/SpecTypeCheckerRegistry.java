package com.team4u.framework.flow.definition.type;

import com.team4u.framework.flow.compiler.FrozenKeyedPolicyRegistry;
import com.team4u.framework.flow.definition.model.FlowSpec;

/**
 * 流程规范类型检查器策略注册表（Spec Type Checker Registry）。
 *
 * <p>基于 {@link FrozenKeyedPolicyRegistry} 统一管理各类 {@link FlowSpec} 对应的 {@link SpecTypeChecker} 实现，
 * 静态初始化后自动冻结为只读。</p>
 *
 * @author jay.wu
 */
public final class SpecTypeCheckerRegistry extends FrozenKeyedPolicyRegistry<Class<? extends FlowSpec>, SpecTypeChecker<?>> {

    private static final SpecTypeCheckerRegistry GLOBAL = new SpecTypeCheckerRegistry();

    static {
        GLOBAL.register(new SpecTypeCheckers.StepSpecTypeChecker());
        GLOBAL.register(new SpecTypeCheckers.SequenceSpecTypeChecker());
        GLOBAL.register(new SpecTypeCheckers.RouteSpecTypeChecker());
        GLOBAL.register(new SpecTypeCheckers.FirstApplicableSpecTypeChecker());
        GLOBAL.register(new SpecTypeCheckers.RecoverSpecTypeChecker());
        GLOBAL.register(new SpecTypeCheckers.ParallelSpecTypeChecker());
        GLOBAL.register(new SpecTypeCheckers.AwaitSpecTypeChecker());
        GLOBAL.register(new SpecTypeCheckers.CompleteSpecTypeChecker());
        GLOBAL.register(new SpecTypeCheckers.ControlSpecTypeChecker());
        GLOBAL.freeze();
    }

    /**
     * 获取全局共享的 SpecTypeCheckerRegistry 实例。
     *
     * @return 全局注册表
     */
    public static SpecTypeCheckerRegistry global() {
        return GLOBAL;
    }

    @SuppressWarnings("unchecked")
    public SpecTypeCheckerRegistry() {
        super(SpecTypeChecker.class);
    }
}
