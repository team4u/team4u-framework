package com.team4u.framework.config.core.support;

import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.spi.InstanceResolutionStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 内置单键文档型实例解析策略
 * <p>
 * 适用于单个完整配置键（如 JSON 字符串）驱动单个实例或通配符驱动多实例池的场景。
 * 遵循键级删除语义（配置键值清空即下线对应实例）。
 * </p>
 *
 * @param <T> 实例类型
 */
public class SingleKeyResolutionStrategy<T> implements InstanceResolutionStrategy<T> {

    private static final Logger log = LoggerFactory.getLogger(ConfigDrivenRegistry.class);

    private final String keyPrefix;
    private final boolean singleKeyMode;
    private final BiFunction<String, String, T> instanceFactory;

    public SingleKeyResolutionStrategy(String keyOrPattern,
                                       boolean singleInstance,
                                       Function<String, T> instanceFactory) {
        this(keyOrPattern, singleInstance, (key, rawConfig) -> {
            if (instanceFactory == null) {
                throw new IllegalArgumentException("instanceFactory must not be null");
            }
            return instanceFactory.apply(rawConfig);
        });
    }

    public SingleKeyResolutionStrategy(String keyOrPattern,
                                       boolean singleInstance,
                                       BiFunction<String, String, T> instanceFactory) {
        if (keyOrPattern == null || keyOrPattern.trim().isEmpty()) {
            throw new IllegalArgumentException("keyOrPattern must not be empty");
        }
        if (instanceFactory == null) {
            throw new IllegalArgumentException("instanceFactory must not be null");
        }
        String trimmedPattern = keyOrPattern.trim();
        this.singleKeyMode = singleInstance;
        this.instanceFactory = instanceFactory;
        if (singleInstance) {
            this.keyPrefix = trimmedPattern;
        } else {
            this.keyPrefix = trimmedPattern.contains("*")
                    ? trimmedPattern.substring(0, trimmedPattern.indexOf('*'))
                    : trimmedPattern;
        }
    }

    @Override
    public Optional<String> defaultInstanceKey() {
        return singleKeyMode ? Optional.of(this.keyPrefix) : Optional.empty();
    }

    @Override
    public String resolveInstanceKey(String configKey) {
        if (configKey == null || configKey.trim().isEmpty()) {
            return this.keyPrefix;
        }
        if (singleKeyMode) {
            return configKey;
        }
        if (configKey.startsWith(this.keyPrefix)) {
            return configKey;
        }
        return this.keyPrefix + configKey;
    }

    @Override
    public T createInstance(ConfigManager configManager, String instanceKey) {
        String rawConfig = configManager.getString(instanceKey).orElse(null);
        return doCreateInstance(instanceKey, rawConfig);
    }

    @Override
    public void onConfigChanged(ConfigDrivenRegistry<T> registry, String changedKey, String oldValue, String newValue) {
        if (newValue == null || newValue.trim().isEmpty()) {
            log.info("Config deleted for key [{}], removing instance.", changedKey);
            registry.removeAndClose(changedKey);
            return;
        }

        log.info("Config changed for key [{}], attempting to hot-reload instance.", changedKey);
        registry.safeSwap(changedKey, () -> doCreateInstance(changedKey, newValue));
    }

    private T doCreateInstance(String key, String rawConfig) {
        if (rawConfig == null || rawConfig.trim().isEmpty()) {
            return null;
        }
        return instanceFactory.apply(key, rawConfig);
    }
}
