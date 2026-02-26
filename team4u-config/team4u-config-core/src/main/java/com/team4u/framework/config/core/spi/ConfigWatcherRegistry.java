package com.team4u.framework.config.core.spi;

import com.team4u.framework.policy.core.OrderedPolicyChain;

/**
 * 配置监听器注册表
 * <p>
 * 管理所有的 {@link ConfigWatcher} 实例，支持按优先级排序执行。
 * </p>
 *
 * @author jay.wu
 */
public class ConfigWatcherRegistry extends OrderedPolicyChain<Void, ConfigWatcher> {

    private static final ConfigWatcherRegistry GLOBAL = new ConfigWatcherRegistry();

    /**
     * 获取全局共享的配置监听器注册表实例
     *
     * @return 全局注册表实例
     */
    public static ConfigWatcherRegistry global() {
        return GLOBAL;
    }

    /**
     * 创建一个新的配置监听器注册表
     * <p>
     * 推荐在需要完全隔离的测试环境或多配置中心并存的场景下手动创建。
     * 一般建议使用 {@link #global()} 获取全局共享实例。
     * </p>
     */
    public ConfigWatcherRegistry() {
        super(ConfigWatcher.class);
    }
}
