package com.team4u.framework.config.core;

import com.team4u.framework.config.core.convert.PropertyConverter;
import com.team4u.framework.config.core.convert.PropertyConverterRegistry;
import com.team4u.framework.config.core.spi.ConfigSource;
import com.team4u.framework.config.core.spi.ConfigSourceRegistry;
import com.team4u.framework.config.core.spi.ConfigWatcher;
import com.team4u.framework.config.core.spi.ConfigWatcherRegistry;

/**
 * 配置管理模块全局引导配置类
 * <p>
 * 提供统一的入口进行全局配置源、监听器及属性转换器的注册，避免注册逻辑散落在各处。
 * 支持锁定机制，确保应用启动后的稳定性。
 */
public class ConfigBootstrap {

    private static final ConfigBootstrap INSTANCE = new ConfigBootstrap();

    private volatile boolean locked = false;

    private ConfigBootstrap() {
    }

    /**
     * 获取全局引导实例
     */
    public static ConfigBootstrap global() {
        return INSTANCE;
    }

    /**
     * 注册全局配置源
     *
     * @param source 配置源实例
     */
    public synchronized ConfigBootstrap registerSource(ConfigSource source) {
        checkLocked();
        ConfigSourceRegistry.global().register(source);
        return this;
    }

    /**
     * 注册全局配置监听器
     *
     * @param watcher 监听器实例
     */
    public synchronized ConfigBootstrap registerWatcher(ConfigWatcher watcher) {
        checkLocked();
        ConfigWatcherRegistry.global().register(watcher);
        return this;
    }

    /**
     * 注册全局属性转换器
     *
     * @param converter 转换器实例
     */
    public synchronized ConfigBootstrap registerConverter(PropertyConverter<?> converter) {
        checkLocked();
        PropertyConverterRegistry.global().register(converter);
        return this;
    }

    /**
     * 锁定全局注册表
     * <p>
     * 调用后将禁止任何新的注册操作，建议在应用启动完成（如 Spring 启动成功）后调用。
     */
    public synchronized void lock() {
        this.locked = true;
    }

    /**
     * 检查是否已锁定
     */
    private void checkLocked() {
        if (locked) {
            throw new IllegalStateException("Config global registry is locked, no more registrations allowed.");
        }
    }
}
