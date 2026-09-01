package com.team4u.framework.flow.definition.binding;

import com.team4u.framework.flow.compiler.FrozenKeyedPolicyRegistry;
import com.team4u.framework.flow.definition.model.FlowSpec;

/**
 * 流程规范绑定器策略注册表（Spec Binder Registry）。
 *
 * <p>基于 {@link FrozenKeyedPolicyRegistry} 统一管理各类 {@link FlowSpec} 对应的 {@link SpecBinder} 实现，
 * 静态初始化后自动冻结为只读。</p>
 *
 * @author jay.wu
 */
public final class SpecBinderRegistry extends FrozenKeyedPolicyRegistry<Class<? extends FlowSpec>, SpecBinder<?>> {

    private static final SpecBinderRegistry GLOBAL = new SpecBinderRegistry();

    static {
        GLOBAL.register(new SpecBinders.StepSpecBinder());
        GLOBAL.register(new SpecBinders.SequenceSpecBinder());
        GLOBAL.register(new SpecBinders.RouteSpecBinder());
        GLOBAL.register(new SpecBinders.FirstApplicableSpecBinder());
        GLOBAL.register(new SpecBinders.RecoverSpecBinder());
        GLOBAL.register(new SpecBinders.ParallelSpecBinder());
        GLOBAL.register(new SpecBinders.AwaitSpecBinder());
        GLOBAL.register(new SpecBinders.CompleteSpecBinder());
        GLOBAL.register(new SpecBinders.ControlSpecBinder());
        GLOBAL.freeze();
    }

    /**
     * 获取全局共享的 SpecBinderRegistry 实例。
     *
     * @return 全局注册表
     */
    public static SpecBinderRegistry global() {
        return GLOBAL;
    }

    @SuppressWarnings("unchecked")
    public SpecBinderRegistry() {
        super(SpecBinder.class);
    }
}
