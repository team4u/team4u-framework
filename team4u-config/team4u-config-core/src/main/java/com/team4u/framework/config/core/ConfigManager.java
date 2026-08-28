package com.team4u.framework.config.core;

import com.team4u.framework.config.core.annotation.ConfigPrefix;
import com.team4u.framework.config.core.convert.PropertyConverter;
import com.team4u.framework.config.core.convert.PropertyConverterRegistry;
import com.team4u.framework.config.core.domain.ConfigSnapshot;
import com.team4u.framework.config.core.internal.DefaultConfigManager;
import com.team4u.framework.config.core.spi.*;
import com.team4u.framework.policy.api.PolicyRegistry;
import com.team4u.framework.policy.util.PolicyScanner;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * 配置管理中心系统总控门面接口
 * <p>
 * 推荐通过 {@link ConfigManager#builder()} 构建自定义配置管理器实例，
 * 或使用 {@link ConfigManager#global()} 获取全局共享的标准实例。
 * </p>
 *
 * <pre>
 * // 使用标准门面获取配置
 * ConfigManager manager = ConfigManager.global();
 * String dbUrl = manager.getString("db.url").orElse(null);
 * DbConfig dbConfig = manager.createProxy("db", DbConfig.class);
 *
 * // 定制特定业务线的配置源
 * ConfigManager customManager = ConfigManager.builder()
 *         .addSource(new MyCustomConfigSource())
 *         .addWatcher(new MyCustomConfigWatcher())
 *         .build();
 * customManager.getString("custom.key");
 * </pre>
 */
public interface ConfigManager {

    /**
     * 获取全局标准单例配置管理引擎
     * <p>
     * 该实例通过 SPI (ServiceLoader) 机制自动发现并加载 {@link ConfigSource} 和
     * {@link ConfigWatcher}。
     * </p>
     *
     * @return 全局单例配置管理实例
     */
    static ConfigManager global() {
        return DefaultConfigManager.global();
    }

    /**
     * 创建配置管理器构造器
     *
     * @return Builder 实例
     */
    static Builder builder() {
        return new Builder();
    }

    /**
     * 获取当前最新的不可变配置快照
     *
     * @return 最新的配置快照
     */
    ConfigSnapshot currentSnapshot();

    /**
     * 生成配置类的动态代理实例
     * <p>
     * 默认返回实时更新模式（Live Mode）的代理，能够感知配置的热更新。
     * </p>
     *
     * @param prefix     配置前缀，用于限定配置搜索范围
     * @param configType 业务层定义的 Java 配置类类型（Bean）
     * @param <T>        配置类强类型
     * @return 动态生成的代理实例
     */
    <T> T createProxy(String prefix, Class<T> configType);

    /**
     * 根据注解自动推断前缀并生成动态代理实例
     * <p>
     * 内部会尝试识别配置类上的 {@link ConfigPrefix} 注解。
     * </p>
     *
     * @param configType 业务层定义的 Java 配置类类型（Bean）
     * @param <T>        配置类强类型
     * @return 动态生成的代理实例
     */
    default <T> T createProxy(Class<T> configType) {
        return createProxy(null, configType);
    }

    /**
     * 获取配置字符串值的快捷方式
     * <p>
     * 内部委托给当前最新的快照执行检索。
     * </p>
     *
     * @param key 配置键
     * @return 配置值的 Optional 包装
     */
    default Optional<String> getString(String key) {
        return currentSnapshot().get(key);
    }

    /**
     * 注册配置变更监听器，并返回可关闭的监听句柄。
     * <p>
     * 支持精确匹配或通配符模式，监听范围取决于实现的模式匹配逻辑。
     * 返回的句柄可用于显式注销该监听器。
     * </p>
     *
     * @param keyPattern 监听的键名或前缀模式（如 "app.db.*"）
     * @param listener   变更回调处理程序
     * @return 可关闭的监听句柄
     */
    AutoCloseable registerChangeListener(String keyPattern, ConfigChangeListener listener);

    /**
     * 配置管理器构造器
     * <p>
     * 负责组装配置源、监听器、转换器以及绑定器。
     * 构建完成后，配置管理器内部状态是不可变且线程安全的。
     * </p>
     */
    class Builder {

        private final ConfigSourceRegistry sourceRegistry;
        private final ConfigWatcherRegistry watcherRegistry;
        private final PropertyConverterRegistry converterRegistry;
        private ConfigProxyCreator proxyCreator;
        /**
         * 防抖时间窗口（毫秒），默认 500ms。设置为 0 或负数时，变更信号将同步执行重载，
         * 适用于单元测试环境以消除 {@code Thread.sleep} 等待。
         */
        private long debounceWindowMs = 500;

        Builder() {
            this(new ConfigSourceRegistry(), new ConfigWatcherRegistry(), new PropertyConverterRegistry());
            init(sourceRegistry);
            init(watcherRegistry);
            init(converterRegistry);
        }

        Builder(ConfigSourceRegistry sourceRegistry,
                ConfigWatcherRegistry watcherRegistry,
                PropertyConverterRegistry converterRegistry) {
            this.sourceRegistry = sourceRegistry;
            this.watcherRegistry = watcherRegistry;
            this.converterRegistry = converterRegistry;
        }

        /**
         * 构建标准全局单例配置管理器
         *
         * @return 标准配置管理器
         */
        public static ConfigManager buildGlobal() {
            return new Builder(
                    ConfigSourceRegistry.global(),
                    ConfigWatcherRegistry.global(),
                    PropertyConverterRegistry.global()).build();
        }

        /**
         * 初始化注册表，执行自动扫描与 SPI 加载
         * <p>
         * 首先扫描当前包下的组件，然后通过标准 SPI 机制加载扩展实现。
         * </p>
         */
        private <P> void init(PolicyRegistry<P> registry) {
            PolicyScanner.scanAndRegister(registry);
            PolicyScanner.registerFromServiceLoader(registry);
        }

        /**
         * 指定包路径扫描配置源实现
         *
         * @param packageName 包名
         * @return 当前 Builder 实例
         */
        public Builder scanSources(String packageName) {
            PolicyScanner.scanAndRegister(sourceRegistry, packageName);
            return this;
        }

        /**
         * 指定包路径扫描配置监听器实现
         *
         * @param packageName 包名
         * @return 当前 Builder 实例
         */
        public Builder scanWatchers(String packageName) {
            PolicyScanner.scanAndRegister(watcherRegistry, packageName);
            return this;
        }

        /**
         * 指定包路径扫描属性转换器实现
         *
         * @param packageName 包名
         * @return 当前 Builder 实例
         */
        public Builder scanConverters(String packageName) {
            PolicyScanner.scanAndRegister(converterRegistry, packageName);
            return this;
        }

        /**
         * 设置配置代理创建器
         *
         * @param proxyCreator 代理创建器实例
         * @return 当前 Builder 实例
         */
        public Builder proxyCreator(ConfigProxyCreator proxyCreator) {
            this.proxyCreator = proxyCreator;
            return this;
        }

        /**
         * 手动添加配置源
         *
         * @param source 配置源实例
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
         * 手动添加配置监听器
         *
         * @param watcher 监听器实例
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
         * 手动添加自定义属性转换器
         *
         * @param converter 转换器实例
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
         * 设置防抖时间窗口
         * <p>
         * 生产环境默认 500ms，可有效过滤密集变更；测试环境传入 0 可实现变更信号同步执行重载，
         * 彻底消除测试中的 {@code Thread.sleep} 等待。
         * </p>
         *
         * @param debounceWindowMs 防抖时间窗口，单位毫秒，传入 0 或负数表示同步重载
         * @return 当前 Builder 实例
         */
        public Builder debounceWindow(long debounceWindowMs) {
            this.debounceWindowMs = debounceWindowMs;
            return this;
        }

        /**
         * 构建配置管理器实例
         *
         * @return 配置管理器实例
         */
        public ConfigManager build() {
            return new DefaultConfigManager(sourceRegistry, watcherRegistry, converterRegistry,
                    resolveProxyCreator(), debounceWindowMs);
        }

        private ConfigProxyCreator resolveProxyCreator() {
            if (proxyCreator != null) {
                return proxyCreator;
            }

            List<ConfigProxyCreator> discovered = new ArrayList<>();
            for (ConfigProxyCreator candidate : ServiceLoader.load(
                    ConfigProxyCreator.class, contextClassLoader())) {
                discovered.add(candidate);
            }
            if (discovered.isEmpty()) {
                return null;
            }
            if (discovered.size() == 1) {
                return discovered.get(0);
            }
            throw new IllegalStateException(
                    "Multiple ConfigProxyCreator implementations were found: " + discovered);
        }

        private static ClassLoader contextClassLoader() {
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            return classLoader == null
                    ? ConfigProxyCreator.class.getClassLoader() : classLoader;
        }
    }
}
