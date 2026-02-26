package com.team4u.framework.config.core;

import com.team4u.framework.config.core.convert.PropertyConverterRegistry;
import com.team4u.framework.config.core.spi.ConfigSourceRegistry;
import com.team4u.framework.config.core.spi.ConfigWatcherRegistry;

/**
 * 全局共享注册表持有者
 * <p>
 * 它是 Spring 和 ConfigManager 之间的桥梁。
 * Spring 往这里“填”数据，ConfigManager 从这里“读”数据。
 * </p>
 *
 * @author gemini-cli
 */
public class GlobalConfigRegistries {

    /**
     * 全局共享配置源注册表
     */
    private static final ConfigSourceRegistry SOURCE_REGISTRY = new ConfigSourceRegistry();

    /**
     * 全局共享配置监控注册表
     */
    private static final ConfigWatcherRegistry WATCHER_REGISTRY = new ConfigWatcherRegistry();

    /**
     * 全局共享属性转换注册表
     */
    private static final PropertyConverterRegistry CONVERTER_REGISTRY = new PropertyConverterRegistry();

    public static ConfigSourceRegistry getSourceRegistry() {
        return SOURCE_REGISTRY;
    }

    public static ConfigWatcherRegistry getWatcherRegistry() {
        return WATCHER_REGISTRY;
    }

    public static PropertyConverterRegistry getConverterRegistry() {
        return CONVERTER_REGISTRY;
    }
}
