package com.team4u.framework.router.factory;

import com.team4u.framework.policy.core.KeyedPolicyRegistry;
import com.team4u.framework.router.spi.RouterFactory;

/**
 * 路由工厂注册表 (Router Factory Registry)
 * <p>
 * 统一管理所有已注册的路由器工厂。它基于策略模式，允许通过路由类型的标识符（如 "weight", "map", "expression"）
 * 查找并获取对应的 {@link RouterFactory} 来创建路由器实例。
 * </p>
 */
public class RouterFactoryRegistry extends KeyedPolicyRegistry<String, RouterFactory> {

    private static final RouterFactoryRegistry GLOBAL = new RouterFactoryRegistry();

    /**
     * 创建一个新的路由工厂注册表
     */
    public RouterFactoryRegistry() {
        super(RouterFactory.class);
    }

    /**
     * 获取全局共享的路由工厂注册表实例
     *
     * @return 全局注册表实例
     */
    public static RouterFactoryRegistry global() {
        return GLOBAL;
    }
}
