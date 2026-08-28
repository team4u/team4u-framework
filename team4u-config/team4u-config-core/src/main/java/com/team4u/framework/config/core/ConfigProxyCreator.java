package com.team4u.framework.config.core;

public interface ConfigProxyCreator {
    <T> T create(ConfigProxyContext context, String prefix, Class<T> configType);
}
