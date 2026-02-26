package com.team4u.framework.config.core.spi;

import com.team4u.framework.policy.core.OrderedPolicyChain;

/**
 * 配置源注册表
 * <p>
 * 基于优先级链条管理所有的 {@link ConfigSource} 实例。
 * </p>
 *
 * @author jay.wu
 */
public class ConfigSourceRegistry extends OrderedPolicyChain<Void, ConfigSource> {

    private static final ConfigSourceRegistry GLOBAL = new ConfigSourceRegistry();

    /**
     * 获取全局共享的配置源注册表实例
     *
     * @return 全局注册表实例
     */
    public static ConfigSourceRegistry global() {
        return GLOBAL;
    }

    /**
     * 创建一个新的配置源注册表
     * <p>
     * 推荐在需要完全隔离的测试环境或多配置中心并存的场景下手动创建。
     * 一般建议使用 {@link #global()} 获取全局共享实例。
     * </p>
     */
    public ConfigSourceRegistry() {
        super(ConfigSource.class);
    }
}
