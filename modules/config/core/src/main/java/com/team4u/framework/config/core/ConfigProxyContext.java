package com.team4u.framework.config.core;

import com.team4u.framework.config.core.convert.PropertyConverterRegistry;

public interface ConfigProxyContext {
    ConfigManager manager();

    PropertyConverterRegistry converterRegistry();
}
