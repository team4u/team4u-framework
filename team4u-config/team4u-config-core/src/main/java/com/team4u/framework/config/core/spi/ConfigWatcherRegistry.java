package com.team4u.framework.config.core.spi;

import com.team4u.framework.policy.OrderedPolicyChain;

/**
 * 配置监听器注册表
 *
 * @author jay.wu
 */
public class ConfigWatcherRegistry extends OrderedPolicyChain<Void, ConfigWatcher> {

    public ConfigWatcherRegistry() {
        super(ConfigWatcher.class);
    }
}
