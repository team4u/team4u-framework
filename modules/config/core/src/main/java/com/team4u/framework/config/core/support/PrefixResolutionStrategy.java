package com.team4u.framework.config.core.support;

import com.team4u.framework.base.util.MapReader;
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.domain.ConfigSnapshot;
import com.team4u.framework.config.core.spi.InstanceResolutionStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 内置扁平键前缀树实例解析策略
 * <p>
 * 适用于散落在多条独立属性键下的扁平配置树驱动单实例或多实例池的场景。
 * 具备子树指纹比对去重、分层删除语义（整子树清空时才下线实例）与多子项配置隔离特性。
 * </p>
 *
 * @param <T> 实例类型
 */
public class PrefixResolutionStrategy<T> implements InstanceResolutionStrategy<T> {

    private static final Logger log = LoggerFactory.getLogger(ConfigDrivenRegistry.class);

    private final String cleanPrefix;
    private final String prefixWithDot;
    private final boolean singleKeyMode;
    private final BiFunction<String, MapReader, T> instanceFactory;

    // 前缀模式下的子树内容指纹缓存：Key 为实例标识，Value 为上次构建时的子树键值映射
    private final Map<String, Map<String, String>> fingerprints = new ConcurrentHashMap<>();

    public PrefixResolutionStrategy(String prefix,
                                    boolean singleInstance,
                                    Function<MapReader, T> instanceFactory) {
        this(prefix, singleInstance, (id, reader) -> {
            if (instanceFactory == null) {
                throw new IllegalArgumentException("instanceFactory must not be null");
            }
            return instanceFactory.apply(reader);
        });
    }

    public PrefixResolutionStrategy(String prefix,
                                    boolean singleInstance,
                                    BiFunction<String, MapReader, T> instanceFactory) {
        if (prefix == null || prefix.trim().isEmpty()) {
            throw new IllegalArgumentException("prefix must not be empty");
        }
        if (instanceFactory == null) {
            throw new IllegalArgumentException("instanceFactory must not be null");
        }
        String rawPrefix = prefix.trim();
        this.cleanPrefix = rawPrefix.endsWith(".") ? rawPrefix.substring(0, rawPrefix.length() - 1) : rawPrefix;
        this.prefixWithDot = this.cleanPrefix + ".";
        this.singleKeyMode = singleInstance;
        this.instanceFactory = instanceFactory;
    }

    @Override
    public Optional<String> defaultInstanceKey() {
        return singleKeyMode ? Optional.of(this.cleanPrefix) : Optional.empty();
    }

    @Override
    public String resolveInstanceKey(String configKey) {
        if (singleKeyMode) {
            return this.cleanPrefix;
        }
        if (configKey == null || configKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Sub-key must not be empty in multi-instance mode");
        }
        String trimmed = configKey.trim();
        if (trimmed.startsWith(this.prefixWithDot)) {
            trimmed = trimmed.substring(this.prefixWithDot.length());
        } else if (trimmed.equals(this.cleanPrefix)) {
            throw new IllegalArgumentException("Cannot get root prefix in multi-instance mode: " + configKey);
        }
        int dotIndex = trimmed.indexOf('.');
        return dotIndex != -1 ? trimmed.substring(0, dotIndex) : trimmed;
    }

    @Override
    public T createInstance(ConfigManager configManager, String instanceId) {
        String subtreePrefix = resolveSubtreePrefix(instanceId);
        ConfigSnapshot snapshot = configManager.currentSnapshot();
        Map<String, String> prefixMap = snapshot.getByPrefix(subtreePrefix);
        if (prefixMap.isEmpty()) {
            return null;
        }
        MapReader reader = snapshot.asReader(subtreePrefix);
        T instance = instanceFactory.apply(instanceId, reader);
        if (instance != null) {
            fingerprints.put(instanceId, prefixMap);
        }
        return instance;
    }

    @Override
    public void onConfigChanged(ConfigDrivenRegistry<T> registry, String changedKey, String oldValue, String newValue) {
        String instanceId = extractInstanceIdFromChangedKey(changedKey);
        if (instanceId == null) {
            return;
        }

        String subtreePrefix = resolveSubtreePrefix(instanceId);
        ConfigSnapshot snapshot = registry.getConfigManager().currentSnapshot();
        Map<String, String> currentMap = snapshot.getByPrefix(subtreePrefix);

        // 分层删除语义：若前缀下无任何有效条目（或全部失效），移除并关闭实例
        if (currentMap.isEmpty()) {
            log.info("Config subtree deleted for instance [{}], removing instance.", instanceId);
            registry.removeAndClose(instanceId);
            return;
        }

        // 指纹比对去重：内容未变则跳过重建（合并单次重载中同前缀下多键变更的回调风暴）
        Map<String, String> lastFingerprint = fingerprints.get(instanceId);
        if (currentMap.equals(lastFingerprint)) {
            log.debug("Config subtree for instance [{}] unchanged, skipping rebuild.", instanceId);
            return;
        }

        log.info("Config subtree changed for instance [{}], attempting to hot-reload instance.", instanceId);
        registry.safeSwap(instanceId, () -> {
            MapReader reader = snapshot.asReader(subtreePrefix);
            return instanceFactory.apply(instanceId, reader);
        }, newInstance -> fingerprints.put(instanceId, currentMap));
    }

    @Override
    public void onInstanceRemoved(String instanceKey) {
        fingerprints.remove(instanceKey);
    }

    @Override
    public void destroy() {
        fingerprints.clear();
    }

    /**
     * 解析前缀模式下的子树前缀路径
     */
    private String resolveSubtreePrefix(String instanceId) {
        if (singleKeyMode) {
            return this.cleanPrefix;
        }
        return this.cleanPrefix + "." + instanceId;
    }

    /**
     * 从变更键提取实例标识
     */
    private String extractInstanceIdFromChangedKey(String changedKey) {
        if (changedKey == null || !changedKey.startsWith(this.prefixWithDot)) {
            return null;
        }
        if (singleKeyMode) {
            return this.cleanPrefix;
        }
        String remainder = changedKey.substring(this.prefixWithDot.length());
        if (remainder.isEmpty()) {
            return null;
        }
        int dotIndex = remainder.indexOf('.');
        return dotIndex != -1 ? remainder.substring(0, dotIndex) : remainder;
    }
}
