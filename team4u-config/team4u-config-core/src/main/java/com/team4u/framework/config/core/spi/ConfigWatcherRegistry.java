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

    public ConfigWatcherRegistry() {
        super(ConfigWatcher.class);
    }
}
