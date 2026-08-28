package com.team4u.framework.config.core;

import com.team4u.framework.config.core.proxy.ConfigProxyFactory;

public class TestConfigProxyCreator implements ConfigProxyCreator {
    @Override
    public <T> T create(ConfigProxyContext context, String prefix, Class<T> configType) {
        return new ConfigProxyFactory(context.converterRegistry())
                .createLiveProxy(context.manager(), prefix, configType);
    }
}
