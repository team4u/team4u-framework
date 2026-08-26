package com.team4u.framework.config.core.support;

import com.team4u.framework.config.core.ConfigManager;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 配置驱动的实例注册表
 * <p>
 * 管理配置项与实例的映射关系，监听变更并实现安全热更新。
 * </p>
 *
 * @param <T> 实例类型
 */
public class ConfigDrivenRegistry<T> {

    private static final Logger log = LoggerFactory.getLogger(ConfigDrivenRegistry.class);

    @Getter
    private final ConfigManager configManager;
    @Getter
    private final String keyPrefix;
    @Getter
    private final boolean singleKeyMode;
    private final Function<String, T> instanceFactory;
    private final AutoCloseable listenerHandle;

    // 实例缓存：Key 为配置键，Value 为对象实例
    private final Map<String, T> instanceCache = new ConcurrentHashMap<>();

    /**
     * @param configManager   配置管理器
     * @param keyOrPrefix     配置键或前缀（如 "router."、"router.*" 或 "team4u.log.finops"）
     * @param instanceFactory 实例工厂：输入配置内容，输出对象实例
     */
    public ConfigDrivenRegistry(ConfigManager configManager, String keyOrPrefix, Function<String, T> instanceFactory) {
        this.configManager = configManager;
        this.instanceFactory = instanceFactory;

        if (keyOrPrefix == null || keyOrPrefix.trim().isEmpty()) {
            throw new IllegalArgumentException("keyOrPrefix must not be empty");
        }

        // 判断是否为前缀多实例模式：以 "." 或 ".*" 结尾视为前缀，否则视为单 Key 精确配置
        if (keyOrPrefix.endsWith(".") || keyOrPrefix.endsWith(".*")) {
            this.singleKeyMode = false;
            this.keyPrefix = keyOrPrefix.endsWith(".*")
                    ? keyOrPrefix.substring(0, keyOrPrefix.length() - 1)
                    : keyOrPrefix;
            // 注册变更监听器：前缀通配符匹配（如 "router.*"）
            this.listenerHandle = this.configManager.registerChangeListener(this.keyPrefix + "*", this::onConfigChanged);
        } else {
            this.singleKeyMode = true;
            this.keyPrefix = keyOrPrefix;
            // 注册变更监听器：单 Key 精确匹配，防止误触同前缀其他键
            this.listenerHandle = this.configManager.registerChangeListener(this.keyPrefix, this::onConfigChanged);
        }
    }

    /**
     * 创建基于前缀的多实例注册表工厂方法
     *
     * @param configManager   配置管理器
     * @param prefix          配置前缀（自动补全末尾 "."）
     * @param instanceFactory 实例工厂
     * @param <T>             实例类型
     * @return ConfigDrivenRegistry 实例
     */
    public static <T> ConfigDrivenRegistry<T> byPrefix(ConfigManager configManager, String prefix, Function<String, T> instanceFactory) {
        if (prefix == null || prefix.trim().isEmpty()) {
            throw new IllegalArgumentException("prefix must not be empty");
        }
        String normalized = prefix.endsWith(".") ? prefix : prefix + ".";
        return new ConfigDrivenRegistry<>(configManager, normalized, instanceFactory);
    }

    /**
     * 创建基于单一 Key 的注册表工厂方法
     *
     * @param configManager   配置管理器
     * @param key             精确配置键
     * @param instanceFactory 实例工厂
     * @param <T>             实例类型
     * @return ConfigDrivenRegistry 实例
     */
    public static <T> ConfigDrivenRegistry<T> byKey(ConfigManager configManager, String key, Function<String, T> instanceFactory) {
        return new ConfigDrivenRegistry<>(configManager, key, instanceFactory);
    }

    /**
     * 获取单配置实例（仅适用于单 Key 模式）
     *
     * @return 实例对象
     * @throws UnsupportedOperationException 当注册表为前缀多实例模式时抛出
     */
    public T get() {
        if (!singleKeyMode) {
            throw new UnsupportedOperationException(
                    "Cannot call no-arg get() on prefix-based registry [" + keyPrefix + "]. Please specify a sub-key.");
        }
        return get(this.keyPrefix);
    }

    /**
     * 获取实例（支持延迟初始化）
     * <p>
     * 在前缀多实例模式下，支持传入短标识（如 "order"）或完整配置键（如 "router.order"）；
     * 在单 Key 模式下，直接传入完整配置键或调用无参 {@link #get()}。
     * </p>
     *
     * @param configKey 完整配置键或短标识
     * @return 实例对象
     */
    public T get(String configKey) {
        String fullKey = resolveFullKey(configKey);
        return instanceCache.computeIfAbsent(fullKey, key -> {
            String rawConfig = configManager.getString(key).orElse(null);
            return createInstance(key, rawConfig);
        });
    }

    /**
     * 解析完整配置键
     */
    private String resolveFullKey(String key) {
        if (key == null || key.trim().isEmpty()) {
            return this.keyPrefix;
        }
        if (singleKeyMode) {
            return key;
        }
        if (key.startsWith(this.keyPrefix)) {
            return key;
        }
        String cleanSubKey = key.startsWith(".") ? key.substring(1) : key;
        return this.keyPrefix + cleanSubKey;
    }

    /**
     * 处理配置变更回调
     */
    private void onConfigChanged(String key, String oldValue, String newValue) {
        if (newValue == null || newValue.trim().isEmpty()) {
            // 配置移除时清理缓存并释放资源
            log.info("Config deleted for key [{}], removing instance.", key);
            removeAndClose(key);
            return;
        }

        log.info("Config changed for key [{}], attempting to hot-reload instance.", key);
        // 安全替换：先构建新实例，成功后执行替换以保证高可用
        try {
            T newInstance = createInstance(key, newValue);
            if (newInstance != null) {
                T oldInstance = instanceCache.put(key, newInstance);
                if (oldInstance != null && oldInstance != newInstance) {
                    closeQuietly(oldInstance);
                }
                log.info("Instance hot-reloaded successfully for key [{}].", key);
            }
        } catch (Exception e) {
            // 热更新构建失败时保留旧实例，确保服务连续性
            log.error("Failed to hot-reload instance for key [{}]. Keeping the old instance.", key, e);
        }
    }

    /**
     * 根据配置内容创建实例
     */
    private T createInstance(String key, String rawConfig) {
        if (rawConfig == null || rawConfig.trim().isEmpty()) {
            return null;
        }
        return instanceFactory.apply(rawConfig);
    }

    /**
     * 移除并关闭实例
     */
    private void removeAndClose(String key) {
        T oldInstance = instanceCache.remove(key);
        closeQuietly(oldInstance);
    }

    /**
     * 优雅关闭资源
     * 识别并关闭实现了 AutoCloseable 接口的实例
     */
    private void closeQuietly(T instance) {
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
        closeListenerQuietly();
        instanceCache.keySet().forEach(this::removeAndClose);
    }

    /**
     * 安静地关闭配置变更监听器句柄。
     * <p>
     * 忽略在注销监听器过程中抛出的异常，防止影响资源清理流程。
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
}
