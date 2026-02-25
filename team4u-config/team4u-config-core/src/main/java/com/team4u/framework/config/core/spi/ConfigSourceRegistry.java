package com.team4u.framework.config.core.spi;

import com.team4u.framework.policy.OrderedPolicyChain;

/**
 * 配置源注册表
 *
 * @author jay.wu
 */
public class ConfigSourceRegistry extends OrderedPolicyChain<Void, ConfigSource> {

    public ConfigSourceRegistry() {
        super(ConfigSource.class);
    }
}
