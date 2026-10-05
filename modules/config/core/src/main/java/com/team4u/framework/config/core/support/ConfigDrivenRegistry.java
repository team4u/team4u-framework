package com.team4u.framework.config.core.support;

import com.team4u.framework.base.util.MapReader;
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.spi.InstanceResolutionStrategy;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 配置驱动的实例注册表
 * <p>
 * 管理配置项与实例的映射关系，监听变更并实现安全热更新。
 * 支持单键文档型配置、扁平键前缀模式以及自定义扩展策略。
 * </p>
 *
 * @param <T> 实例类型
 */
public class ConfigDrivenRegistry<T> {

    private static final Logger log = LoggerFactory.getLogger(ConfigDrivenRegistry.class);

    @Getter
    private final ConfigManager configManager;
    @Getter
    private final String keyOrPattern;
    @Getter
    private final String keyPrefix;

    final InstanceResolutionStrategy<T> strategy;
    private final AutoCloseable listenerHandle;
    private final Map<String, T> instanceCache = new ConcurrentHashMap<>();
    private final Object buildMonitor = new Object();
    private volatile boolean destroyed;
    private final List<ConfigDrivenRegistryListener<T>> listeners = new CopyOnWriteArrayList<>();

    private ConfigDrivenRegistry(ConfigManager configManager,
                                 String keyOrPattern,
                                 String keyPrefix,
                                 InstanceResolutionStrategy<T> strategy) {
        if (configManager == null) {
            throw new IllegalArgumentException("configManager must not be null");
        }
        if (keyOrPattern == null || keyOrPattern.trim().isEmpty()) {
            throw new IllegalArgumentException("keyOrPattern must not be empty");
        }
        if (strategy == null) {
            throw new IllegalArgumentException("strategy must not be null");
        }
        this.configManager = configManager;
        this.strategy = strategy;
        this.keyOrPattern = keyOrPattern.trim();
        this.keyPrefix = keyPrefix;
        this.listenerHandle = this.configManager.registerChangeListener(this.keyOrPattern, this::onConfigChanged);
    }

    /**
     * 基于自定义策略创建配置驱动注册表
     * <p>
     * 监听键规则默认由策略的 {@link InstanceResolutionStrategy#defaultInstanceKey()} 推导，
     * 若未提供默认实例键，则默认监听所有配置变更（"*"）。
     * 工厂返回 null 表示该键无对应实例且不入缓存。
     * </p>
     *
     * @param configManager 配置管理器
     * @param strategy      实例解析与生命周期策略
     * @param <T>           实例类型
     * @return 配置驱动注册表实例
     */
    public static <T> ConfigDrivenRegistry<T> forStrategy(ConfigManager configManager,
                                                          InstanceResolutionStrategy<T> strategy) {
        if (strategy == null) {
            throw new IllegalArgumentException("strategy must not be null");
        }
        String keyOrPattern = strategy.defaultInstanceKey().orElse("*");
        return forStrategy(configManager, keyOrPattern, strategy);
    }

    /**
     * 基于自定义策略与监听键规则创建配置驱动注册表
     * <p>
     * 工厂返回 null 表示该键无对应实例且不入缓存。
     * </p>
     *
     * @param configManager 配置管理器
     * @param keyOrPattern  配置键或通配符规则
     * @param strategy      实例解析与生命周期策略
     * @param <T>           实例类型
     * @return 配置驱动注册表实例
     */
    public static <T> ConfigDrivenRegistry<T> forStrategy(ConfigManager configManager,
                                                          String keyOrPattern,
                                                          InstanceResolutionStrategy<T> strategy) {
        if (keyOrPattern == null || keyOrPattern.trim().isEmpty()) {
            throw new IllegalArgumentException("keyOrPattern must not be empty");
        }
        if (strategy == null) {
            throw new IllegalArgumentException("strategy must not be null");
        }
        String trimmed = keyOrPattern.trim();
        String defaultPrefix = strategy.defaultInstanceKey().orElse(
                trimmed.contains("*") ? trimmed.substring(0, trimmed.indexOf('*')) : trimmed
        );
        return forStrategy(configManager, trimmed, defaultPrefix, strategy);
    }

    /**
     * 基于自定义策略与监听键规则创建配置驱动注册表
     * <p>
     * 工厂返回 null 表示该键无对应实例且不入缓存。
     * </p>
     *
     * @param configManager 配置管理器
     * @param keyOrPattern  配置键或通配符规则
     * @param keyPrefix     配置键前缀
     * @param strategy      实例解析与生命周期策略
     * @param <T>           实例类型
     * @return 配置驱动注册表实例
     */
    public static <T> ConfigDrivenRegistry<T> forStrategy(ConfigManager configManager,
                                                          String keyOrPattern,
                                                          String keyPrefix,
                                                          InstanceResolutionStrategy<T> strategy) {
        return new ConfigDrivenRegistry<>(configManager, keyOrPattern, keyPrefix, strategy);
    }

    /**
     * 创建单键精确单实例注册表
     * <p>
     * 工厂返回 null 表示该键无实例、不入缓存。
     * </p>
     */
    public static <T> ConfigDrivenRegistry<T> forKey(ConfigManager configManager,
                                                     String exactKey,
                                                     Function<String, T> instanceFactory) {
        if (instanceFactory == null) {
            throw new IllegalArgumentException("instanceFactory must not be null");
        }
        return forKey(configManager, exactKey, (key, rawConfig) -> instanceFactory.apply(rawConfig));
    }

    /**
     * 创建单键精确单实例注册表（带配置键）
     * <p>
     * 工厂返回 null 表示该键无实例、不入缓存。
     * </p>
     */
    public static <T> ConfigDrivenRegistry<T> forKey(ConfigManager configManager,
                                                     String exactKey,
                                                     BiFunction<String, String, T> instanceFactory) {
        if (exactKey == null || exactKey.trim().isEmpty()) {
            throw new IllegalArgumentException("exactKey must not be empty");
        }
        String trimmedKey = exactKey.trim();
        return forStrategy(configManager, trimmedKey, trimmedKey,
                new SingleKeyResolutionStrategy<>(trimmedKey, true, instanceFactory));
    }

    /**
     * 创建单键通配符多实例注册表
     * <p>
     * 工厂返回 null 表示该键无实例、不入缓存。
     * </p>
     */
    public static <T> ConfigDrivenRegistry<T> forKeys(ConfigManager configManager,
                                                      String keyPattern,
                                                      Function<String, T> instanceFactory) {
        if (instanceFactory == null) {
            throw new IllegalArgumentException("instanceFactory must not be null");
        }
        return forKeys(configManager, keyPattern, (key, rawConfig) -> instanceFactory.apply(rawConfig));
    }

    /**
     * 创建单键通配符多实例注册表（带配置键）
     * <p>
     * 工厂返回 null 表示该键无实例、不入缓存。
     * </p>
     */
    public static <T> ConfigDrivenRegistry<T> forKeys(ConfigManager configManager,
                                                      String keyPattern,
                                                      BiFunction<String, String, T> instanceFactory) {
        if (keyPattern == null || keyPattern.trim().isEmpty()) {
            throw new IllegalArgumentException("keyPattern must not be empty");
        }
        String trimmedPattern = keyPattern.trim();
        String keyPrefix = trimmedPattern.contains("*")
                ? trimmedPattern.substring(0, trimmedPattern.indexOf('*'))
                : trimmedPattern;
        return forStrategy(configManager, trimmedPattern, keyPrefix,
                new SingleKeyResolutionStrategy<>(trimmedPattern, false, instanceFactory));
    }

    /**
     * 创建前缀单实例注册表
     * <p>
     * 前缀子树为空或工厂返回 null 时表示无实例、不入缓存，获取实例时返回 null。
     * </p>
     */
    public static <T> ConfigDrivenRegistry<T> forPrefix(ConfigManager configManager,
                                                        String prefix,
                                                        Function<MapReader, T> instanceFactory) {
        if (instanceFactory == null) {
            throw new IllegalArgumentException("instanceFactory must not be null");
        }
        return forPrefix(configManager, prefix, (id, reader) -> instanceFactory.apply(reader));
    }

    /**
     * 创建前缀单实例注册表（带实例标识）
     * <p>
     * 前缀子树为空或工厂返回 null 时表示无实例、不入缓存，获取实例时返回 null。
     * </p>
     */
    public static <T> ConfigDrivenRegistry<T> forPrefix(ConfigManager configManager,
                                                        String prefix,
                                                        BiFunction<String, MapReader, T> instanceFactory) {
        if (prefix == null || prefix.trim().isEmpty()) {
            throw new IllegalArgumentException("prefix must not be empty");
        }
        String rawPrefix = prefix.trim();
        String cleanPrefix = rawPrefix.endsWith(".") ? rawPrefix.substring(0, rawPrefix.length() - 1) : rawPrefix;
        String keyOrPattern = cleanPrefix + ".*";
        return forStrategy(configManager, keyOrPattern, cleanPrefix,
                new PrefixResolutionStrategy<>(prefix, true, instanceFactory));
    }

    /**
     * 创建前缀多实例注册表
     * <p>
     * 实例对应子树为空或工厂返回 null 时表示无实例、不入缓存，获取实例时返回 null。
     * </p>
     */
    public static <T> ConfigDrivenRegistry<T> forPrefixes(ConfigManager configManager,
                                                          String prefix,
                                                          Function<MapReader, T> instanceFactory) {
        if (instanceFactory == null) {
            throw new IllegalArgumentException("instanceFactory must not be null");
        }
        return forPrefixes(configManager, prefix, (id, reader) -> instanceFactory.apply(reader));
    }

    /**
     * 创建前缀多实例注册表（带实例标识）
     * <p>
     * 实例对应子树为空或工厂返回 null 时表示无实例、不入缓存，获取实例时返回 null。
     * </p>
     */
    public static <T> ConfigDrivenRegistry<T> forPrefixes(ConfigManager configManager,
                                                          String prefix,
                                                          BiFunction<String, MapReader, T> instanceFactory) {
        if (prefix == null || prefix.trim().isEmpty()) {
            throw new IllegalArgumentException("prefix must not be empty");
        }
        String rawPrefix = prefix.trim();
        String cleanPrefix = rawPrefix.endsWith(".") ? rawPrefix.substring(0, rawPrefix.length() - 1) : rawPrefix;
        String prefixWithDot = cleanPrefix + ".";
        String keyOrPattern = prefixWithDot + "*";
        return forStrategy(configManager, keyOrPattern, prefixWithDot,
                new PrefixResolutionStrategy<>(prefix, false, instanceFactory));
    }

    /**
     * 获取单配置实例（仅适用于支持默认实例键的模式）
     * <p>
     * 根据策略推导的默认实例键获取实例。若无对应实例或子树为空，返回 null。
     * </p>
     *
     * @return 实例对象，若配置缺失或工厂返回 null 则返回 null
     * @throws UnsupportedOperationException 当注册表不支持默认实例键时抛出
     */
    public T get() {
        String defaultKey = strategy.defaultInstanceKey().orElseThrow(() ->
                new UnsupportedOperationException(
                        "Cannot call no-arg get() on wildcard-based registry [" + keyOrPattern + "]. Please specify a sub-key."));
        return get(defaultKey);
    }

    /**
     * 注册实例生命周期监听器
     * <p>
     * 监听回调为同步调用。为保证注册表流转的高可用性，实现方抛出的任何异常都会被框架捕获并记录警告日志，绝不阻断后续监听器分发与注册表内部状态流转。
     * </p>
     *
     * @param listener 监听器实例
     * @return 用于注销该监听器的句柄
     */
    public AutoCloseable addListener(ConfigDrivenRegistryListener<T> listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /**
     * 判断注册表是否已被销毁
     *
     * @return 若已销毁返回 true，否则返回 false
     */
    public boolean isDestroyed() {
        return destroyed;
    }

    /**
     * 获取实例（支持延迟初始化）
     * <p>
     * 若对应配置缺失或工厂返回 null，表示该键无实例、不入缓存，返回 null。
     * </p>
     *
     * @param configKey 完整配置键或短标识
     * @return 实例对象，若配置缺失或工厂返回 null 则返回 null
     */
    public T get(String configKey) {
        if (destroyed) {
            return null;
        }
        String instanceKey = strategy.resolveInstanceKey(configKey);
        T instance = instanceCache.get(instanceKey);
        if (instance != null) {
            return instance;
        }
        synchronized (buildMonitor) {
            if (destroyed) {
                return null;
            }
            instance = instanceCache.get(instanceKey);
            if (instance != null) {
                return instance;
            }
            safeSwap(instanceKey, () -> strategy.createInstance(configManager, instanceKey));
            if (destroyed) {
                return null;
            }
            T built = instanceCache.get(instanceKey);
            // 首次构建失败时 safeSwap 已抛出异常，能走到这里说明构建成功或配置缺失
            return built;
        }
    }

    /**
     * 获取默认实例缓存键
     *
     * @return 默认实例缓存键
     */
    public Optional<String> defaultInstanceKey() {
        return strategy.defaultInstanceKey();
    }

    /**
     * 处理配置变更回调
     */
    private void onConfigChanged(String key, String oldValue, String newValue) {
        if (destroyed) {
            return;
        }
        strategy.onConfigChanged(this, key, oldValue, newValue);
    }

    /**
     * 安全替换实例（先构建新实例，成功后执行替换并关闭旧实例）
     * <p>
     * 异常语义：缓存中已有旧实例时视为热重载，构建失败仅记日志并保留旧实例继续服务；
     * 缓存为空时视为首次构建，构建失败将异常重新抛出给调用方（快速失败）。
     * </p>
     *
     * @param instanceKey 实例缓存键
     * @param supplier    新实例提供者
     */
    public void safeSwap(String instanceKey, Supplier<T> supplier) {
        safeSwap(instanceKey, supplier, null);
    }

    /**
     * 安全替换实例（先构建新实例，成功后执行替换并关闭旧实例，并支持成功后置回调）
     *
     * @param instanceKey 实例缓存键
     * @param supplier    新实例提供者
     * @param onSuccess   替换成功后的回调
     */
    public void safeSwap(String instanceKey, Supplier<T> supplier, Consumer<T> onSuccess) {
        if (destroyed) {
            return;
        }
        synchronized (buildMonitor) {
            if (destroyed) {
                return;
            }
            boolean hasOldInstance = instanceCache.containsKey(instanceKey);
            try {
                T newInstance = supplier.get();
                if (destroyed) {
                    closeQuietly(newInstance);
                    return;
                }
                if (newInstance != null) {
                    T oldInstance = instanceCache.put(instanceKey, newInstance);
                    if (onSuccess != null) {
                        onSuccess.accept(newInstance);
                    }
                    if (oldInstance != null && oldInstance != newInstance) {
                        closeQuietly(oldInstance);
                    }
                    if (oldInstance == null) {
                        log.info("Instance created successfully for [{}].", instanceKey);
                        notifyInstanceCreated(instanceKey, newInstance);
                    } else {
                        log.info("Instance hot-reloaded successfully for [{}].", instanceKey);
                        notifyInstanceSwapped(instanceKey, oldInstance, newInstance);
                    }
                }
            } catch (Exception e) {
                notifyInstanceSwapFailed(instanceKey, e);
                if (hasOldInstance) {
                    // 热重载失败：保留旧实例继续服务，确保业务连续性
                    log.error("Failed to hot-reload instance for [{}]. Keeping the old instance.", instanceKey, e);
                } else {
                    // 首次构建失败：快速失败，异常抛给调用方
                    log.error("Failed to create instance for [{}].", instanceKey, e);
                    if (e instanceof RuntimeException) {
                        throw (RuntimeException) e;
                    }
                    throw new IllegalStateException("Failed to create instance for [" + instanceKey + "]", e);
                }
            }
        }
    }

    /**
     * 移除并关闭实例
     *
     * @param instanceKey 实例缓存键
     */
    public void removeAndClose(String instanceKey) {
        synchronized (buildMonitor) {
            strategy.onInstanceRemoved(instanceKey);
            T oldInstance = instanceCache.remove(instanceKey);
            if (oldInstance != null) {
                closeQuietly(oldInstance);
                notifyInstanceRemoved(instanceKey, oldInstance);
            }
        }
    }

    /**
     * 优雅关闭资源，识别并关闭实现了 AutoCloseable 接口的实例
     */
    void closeQuietly(T instance) {
        if (instance instanceof AutoCloseable) {
            try {
                ((AutoCloseable) instance).close();
            } catch (Exception e) {
                log.warn("Error occurred while closing old instance.", e);
            }
        }
    }

    /**
     * 销毁注册表，释放所有实例资源
     */
    public void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        closeListenerQuietly();
        synchronized (buildMonitor) {
            new ArrayList<>(instanceCache.keySet()).forEach(this::removeAndClose);
            strategy.destroy();
            listeners.clear();
        }
    }

    /**
     * 安静地关闭配置变更监听器句柄
     */
    private void closeListenerQuietly() {
        if (listenerHandle != null) {
            try {
                listenerHandle.close();
            } catch (Exception e) {
                log.warn("Error occurred while closing config change listener.", e);
            }
        }
    }

    private void notifyInstanceCreated(String instanceKey, T instance) {
        for (ConfigDrivenRegistryListener<T> listener : listeners) {
            try {
                listener.onInstanceCreated(instanceKey, instance);
            } catch (Exception e) {
                log.warn("Error occurred while invoking onInstanceCreated for instance [{}].", instanceKey, e);
            }
        }
    }

    private void notifyInstanceSwapped(String instanceKey, T oldInstance, T newInstance) {
        for (ConfigDrivenRegistryListener<T> listener : listeners) {
            try {
                listener.onInstanceSwapped(instanceKey, oldInstance, newInstance);
            } catch (Exception e) {
                log.warn("Error occurred while invoking onInstanceSwapped for instance [{}].", instanceKey, e);
            }
        }
    }

    private void notifyInstanceSwapFailed(String instanceKey, Exception cause) {
        for (ConfigDrivenRegistryListener<T> listener : listeners) {
            try {
                listener.onInstanceSwapFailed(instanceKey, cause);
            } catch (Exception e) {
                log.warn("Error occurred while invoking onInstanceSwapFailed for instance [{}].", instanceKey, e);
            }
        }
    }

    private void notifyInstanceRemoved(String instanceKey, T instance) {
        for (ConfigDrivenRegistryListener<T> listener : listeners) {
            try {
                listener.onInstanceRemoved(instanceKey, instance);
            } catch (Exception e) {
                log.warn("Error occurred while invoking onInstanceRemoved for instance [{}].", instanceKey, e);
            }
        }
    }
}
