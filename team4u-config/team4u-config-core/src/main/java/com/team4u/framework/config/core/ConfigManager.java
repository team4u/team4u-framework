package com.team4u.framework.config.core;

import com.team4u.framework.config.core.annotation.ConfigPrefix;
import com.team4u.framework.config.core.convert.PropertyConverter;
import com.team4u.framework.config.core.convert.PropertyConverterRegistry;
import com.team4u.framework.config.core.domain.ConfigSnapshot;
import com.team4u.framework.config.core.internal.DefaultConfigBinder;
import com.team4u.framework.config.core.internal.DefaultConfigManager;
import com.team4u.framework.config.core.spi.*;
import com.team4u.framework.policy.PolicyRegistry;
import com.team4u.framework.policy.PolicyScanner;

import java.util.Optional;

/**
 * 现代配置中心系统级总控门面接口
 * <p>
 * 推荐通过 {@link ConfigManager#builder()} 构建自定义配置管理器实例，
 * 或使用 {@link ConfigManager#standard()} 获取默认的全局共享标准实例。
 * </p>
 *
 * <pre>
 * // 场景1：普通业务（使用标准门面）
 * ConfigManager manager = ConfigManager.standard();
 * String dbUrl = manager.getString("db.url").orElse(null);
 * DbConfig dbConfig = manager.createProxy("db", DbConfig.class);
 *
 * // 场景2：特定业务线（需要定制 ConfigSource）
 * ConfigManager customManager = ConfigManager.builder()
 *         .addSource(new MyCustomConfigSource())
 *         .addWatcher(new MyCustomConfigWatcher())
 *         .build();
 * customManager.getString("custom.key");
 * </pre>
 */
public interface ConfigManager {

    /**
     * 获取带有标准底层实现的全局单例配置管理引擎。
     * <p>
     * 适用于大多数场景，直接利用 SPI (ServiceLoader) 自动发现 {@link ConfigSource} 和
     * {@link ConfigWatcher}。
     * </p>
     *
     * @return 预置的全局单例配置管理实例
     */
    static ConfigManager standard() {
        if (InstanceHolder.STANDARD_INSTANCE == null) {
            synchronized (ConfigManager.class) {
                if (InstanceHolder.STANDARD_INSTANCE == null) {
                    InstanceHolder.STANDARD_INSTANCE = builder().build();
                }
            }
        }
        return InstanceHolder.STANDARD_INSTANCE;
    }

    /**
     * 重置全局标准实例，主要用于测试或特定的沙箱环境。
     */
    static void resetStandard() {
        synchronized (ConfigManager.class) {
            if (InstanceHolder.STANDARD_INSTANCE instanceof DefaultConfigManager) {
                ((DefaultConfigManager) InstanceHolder.STANDARD_INSTANCE).destroy();
            }
            InstanceHolder.STANDARD_INSTANCE = null;
        }
    }

    /**
     * 创建一个全新的配置管理器建设者
     *
     * @return Builder 实例
     */
    static Builder builder() {
        return new Builder();
    }

    /**
     * 获取当前最高版本最新的不可变配置快照
     *
     * @return 最新的配置快照
     */
    ConfigSnapshot currentSnapshot();

    /**
     * 生成配置接口类型的动态代理实例 (默认返回实时绑定的 Live Mode 代理)
     *
     * @param prefix        绑定的配置前缀
     * @param interfaceType 期望代理出来的业务层 Java 接口类型
     * @param <T>           强类型
     * @return 动态生成的代理实例对象
     */
    <T> T createProxy(String prefix, Class<T> interfaceType);

    /**
     * 生成配置接口类型的动态代理实例（自动识别前缀）
     * <p>
     * 内部会尝试根据 {@link ConfigPrefix} 注解自动推断前缀。
     *
     * @param interfaceType 期望代理出来的业务层 Java 接口类型
     * @param <T>           强类型
     * @return 动态生成的代理实例对象
     */
    default <T> T createProxy(Class<T> interfaceType) {
        return createProxy(null, interfaceType);
    }

    /**
     * 基础键值获取快捷入口，内部委托给 {@link #currentSnapshot()} 执行
     *
     * @param key 精确配置键
     * @return 配置值
     */
    default Optional<String> getString(String key) {
        return currentSnapshot().get(key);
    }

    /**
     * 监听配置点变更
     * <p>
     * (支持精准匹配或是 startWith 等模式，取决于实现内部对于 pattern 的处理机制)
     *
     * @param keyPattern 要监听的键名或前缀模式
     * @param listener   变更回调处理程序
     */
    void addChangeListener(String keyPattern, ConfigChangeListener listener);

    /**
     * 持有单例的内部类
     */
    class InstanceHolder {
        static volatile ConfigManager STANDARD_INSTANCE;
    }

    /**
     * 配置管理器的构造器
     * <p>
     * 用于组装自定义的配置源、配置监听器、配置绑定器。
     * 组装完成后生成的 {@link ConfigManager} 是不可变且线程安全的。
     * </p>
     */
    class Builder {

        private final ConfigSourceRegistry sourceRegistry = new ConfigSourceRegistry();
        private final ConfigWatcherRegistry watcherRegistry = new ConfigWatcherRegistry();
        private final PropertyConverterRegistry converterRegistry = new PropertyConverterRegistry();
        private ConfigBinder configBinder;

        Builder() {
            init(sourceRegistry);
            init(watcherRegistry);
            init(converterRegistry);
        }

        private <P> void init(PolicyRegistry<P> registry) {
            // 1. 自动扫描当前包及其子包
            PolicyScanner.scanAndRegister(registry);
            // 2. 通过 ServiceLoader 加载
            PolicyScanner.registerFromServiceLoader(registry);
        }

        /**
         * 扫描并加载包下所有的 ConfigSource
         *
         * @param packageName 包名
         * @return 当前 Builder 实例
         */
        public Builder scanSources(String packageName) {
            PolicyScanner.scanAndRegister(sourceRegistry, packageName, ConfigSource.class);
            return this;
        }

        /**
         * 扫描并加载包下所有的 ConfigWatcher
         *
         * @param packageName 包名
         * @return 当前 Builder 实例
         */
        public Builder scanWatchers(String packageName) {
            PolicyScanner.scanAndRegister(watcherRegistry, packageName, ConfigWatcher.class);
            return this;
        }

        /**
         * 扫描并加载包下所有的 PropertyConverter
         *
         * @param packageName 包名
         * @return 当前 Builder 实例
         */
        @SuppressWarnings({ "unchecked", "rawtypes" })
        public Builder scanConverters(String packageName) {
            // 由于泛型擦除，强制转换在运行时是安全的
            PolicyScanner.scanAndRegister(converterRegistry, packageName,
                    (Class) PropertyConverter.class);
            return this;
        }

        /**
         * 添加自定义配置源
         *
         * @param source 配置源
         * @return 当前 Builder 实例
         */
        public Builder addSource(ConfigSource... source) {
            if (source != null) {
                for (ConfigSource configSource : source) {
                    sourceRegistry.register(configSource);
                }
            }
            return this;
        }

        /**
         * 添加自定义配置变动监听器
         *
         * @param watcher 配置变动监听器
         * @return 当前 Builder 实例
         */
        public Builder addWatcher(ConfigWatcher... watcher) {
            if (watcher != null) {
                for (ConfigWatcher configWatcher : watcher) {
                    watcherRegistry.register(configWatcher);
                }
            }
            return this;
        }

        /**
         * 添加自定义属性转换器
         *
         * @param converter 属性转换器
         * @return 当前 Builder 实例
         */
        public Builder addConverter(PropertyConverter<?>... converter) {
            if (converter != null) {
                for (PropertyConverter<?> propertyConverter : converter) {
                    converterRegistry.register(propertyConverter);
                }
            }
            return this;
        }

        /**
         * 自定义配置绑定器
         *
         * @param configBinder 配置绑定器
         * @return 当前 Builder 实例
         */
        public Builder configBinder(ConfigBinder configBinder) {
            this.configBinder = configBinder;
            return this;
        }

        /**
         * 构建配置管理器实例
         *
         * @return 配置管理器实例
         */
        public ConfigManager build() {
            if (configBinder == null) {
                configBinder = new DefaultConfigBinder(); // 默认绑定器
            }
            return new DefaultConfigManager(sourceRegistry, watcherRegistry, converterRegistry, configBinder);
        }
    }
}
