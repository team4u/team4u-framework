package com.team4u.framework.flow.engine;

import com.team4u.framework.flow.compiler.FrozenKeyedPolicyRegistry;
import com.team4u.framework.flow.compiler.PlanNode;

/**
 * 治理控制类型策略注册表。
 *
 * <p>静态初始化注册完毕后自动冻结（只读），写入操作抛出
 * {@link UnsupportedOperationException}；自定义扩展点请在冻结前通过自建实例注册，
 * 全局实例仅提供读取。</p>
 *
 * @author jay.wu
 */
public final class ControlKindRegistry extends FrozenKeyedPolicyRegistry<PlanNode.Control.Kind, ControlKindHandler> {

    private static final ControlKindRegistry GLOBAL = new ControlKindRegistry();

    static {
        GLOBAL.register(new ControlKindHandlers.TimeoutHandler());
        GLOBAL.register(new ControlKindHandlers.PolicyHandler());
        GLOBAL.register(new ControlKindHandlers.PersistentPolicyHandler());
        GLOBAL.freeze();
    }

    /**
     * 获取全局共享的控制类型策略注册表。
     */
    public static ControlKindRegistry global() {
        return GLOBAL;
    }

    public ControlKindRegistry() {
        super(ControlKindHandler.class);
    }
}
