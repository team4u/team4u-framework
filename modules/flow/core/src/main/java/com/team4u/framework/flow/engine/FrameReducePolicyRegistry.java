package com.team4u.framework.flow.engine;

import com.team4u.framework.flow.compiler.FrozenKeyedPolicyRegistry;
import com.team4u.framework.flow.compiler.PlanNode;

/**
 * 运行时父帧结果归约策略注册表。
 *
 * <p>静态初始化注册完毕后自动冻结（只读），写入操作抛出
 * {@link UnsupportedOperationException}；自定义扩展点请在冻结前通过自建实例注册，
 * 全局实例仅提供读取。</p>
 *
 * @author jay.wu
 */
public final class FrameReducePolicyRegistry extends FrozenKeyedPolicyRegistry<Class<? extends PlanNode>, FrameReducePolicy<?>> {

    private static final FrameReducePolicyRegistry GLOBAL = new FrameReducePolicyRegistry();

    static {
        GLOBAL.register(new FrameReducePolicies.SequenceReducePolicy());
        GLOBAL.register(new FrameReducePolicies.RouteReducePolicy());
        GLOBAL.register(new FrameReducePolicies.FallbackReducePolicy());
        GLOBAL.register(new FrameReducePolicies.ControlReducePolicy());
        GLOBAL.freeze();
    }

    /**
     * 获取全局共享的帧归约策略注册表。
     */
    public static FrameReducePolicyRegistry global() {
        return GLOBAL;
    }

    @SuppressWarnings("unchecked")
    public FrameReducePolicyRegistry() {
        super(FrameReducePolicy.class);
    }
}
