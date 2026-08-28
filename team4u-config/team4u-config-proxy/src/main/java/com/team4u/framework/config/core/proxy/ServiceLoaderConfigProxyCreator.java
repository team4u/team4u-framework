package com.team4u.framework.config.core.proxy;

import com.team4u.framework.config.core.ConfigProxyContext;
import com.team4u.framework.config.core.ConfigProxyCreator;

public final class ServiceLoaderConfigProxyCreator implements ConfigProxyCreator {

    public ServiceLoaderConfigProxyCreator() {
    }

    @Override
    public <T> T create(ConfigProxyContext context, String prefix, Class<T> configType) {
        return new ConfigProxyFactory(context.converterRegistry())
                .createLiveProxy(context.manager(), prefix, configType);
    }
}
