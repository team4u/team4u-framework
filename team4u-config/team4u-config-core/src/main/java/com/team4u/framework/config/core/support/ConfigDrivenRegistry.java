package com.team4u.framework.config.core.support;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import com.team4u.framework.config.core.ConfigManager;

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

    private static final Log log = LogFactory.get();

    private final ConfigManager configManager;
    private final String keyPrefix;
    private final Function<String, T> instanceFactory;

    // 实例缓存：Key 为配置键，Value 为对象实例
    private final Map<String, T> instanceCache = new ConcurrentHashMap<>();

    /**
     * @param configManager   配置管理器
     * @param keyPrefix       配置前缀，例如：router.
     * @param instanceFactory 实例工厂：输入配置内容，输出对象实例
     */
    public ConfigDrivenRegistry(ConfigManager configManager, String keyPrefix, Function<String, T> instanceFactory) {
        this.configManager = configManager;
        this.keyPrefix = keyPrefix.endsWith(".") ? keyPrefix : keyPrefix + ".";
        this.instanceFactory = instanceFactory;

        // 注册变更监听器
        this.configManager.addChangeListener(this.keyPrefix + "*", this::onConfigChanged);
    }

    /**
     * 获取实例（支持延迟初始化）
     *
     * @param configKey 完整配置键
     * @return 实例对象
     */
    public T get(String configKey) {
        return instanceCache.computeIfAbsent(configKey, key -> {
            String rawConfig = configManager.getString(key).orElse(null);
            return createInstance(key, rawConfig);
        });
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
            log.error(e, "Failed to hot-reload instance for key [{}]. Keeping the old instance.", key);
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
                log.warn(e, "Error occurred while closing old instance.");
            }
        }
    }

    /**
     * 销毁注册表，释放所有实例资源
     */
    public void destroy() {
        instanceCache.keySet().forEach(this::removeAndClose);
    }
}