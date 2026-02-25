package com.team4u.config.core.internal;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import com.team4u.config.core.ConfigChangeListener;
import com.team4u.config.core.ConfigManager;
import com.team4u.config.core.domain.ConfigEntry;
import com.team4u.config.core.domain.ConfigSnapshot;
import com.team4u.config.core.proxy.ConfigProxyFactory;
import com.team4u.config.core.spi.*;
import com.team4u.framework.base.instance.DynamicInstanceProvider;
import lombok.EqualsAndHashCode;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 配置中心默认实现
 */
public class DefaultConfigManager implements ConfigManager {

    private static final Log log = LogFactory.get();

    private final AtomicReference<ConfigSnapshot> snapshotRef = new AtomicReference<>();
    private final List<ConfigSource> sources;
    private final List<ConfigWatcher> watchers;
    private final ConfigBinder configBinder;

    private final SnapshotAggregator aggregator = new SnapshotAggregator();
    private final HotReloadManager hotReloadManager;

    private final List<ListenerRegistration> listeners = new CopyOnWriteArrayList<>();

    private final DynamicInstanceProvider<ProxyKey, ProxyKey, Object> proxyInstanceProvider;

    public DefaultConfigManager(ConfigSourceRegistry sourceRegistry, ConfigWatcherRegistry watcherRegistry,
                                ConfigBinder configBinder) {
        this.sources = sourceRegistry.getPolicies();
        this.watchers = watcherRegistry.getPolicies();
        this.configBinder = configBinder;

        // 初始化所有配置源
        initialLoad();

        // 绑定重载防抖器 (500ms 窗口)
        this.hotReloadManager = new HotReloadManager(
                snapshotRef,
                this.sources,
                this.aggregator,
                500,
                this::fireChangeEvents);

        // 启动 Watchers
        initWatchers();

        // 初始化代理对象缓存提供者
        this.proxyInstanceProvider = DynamicInstanceProvider.createLru(
                1024,
                key -> key,
                key -> doCreateProxy(key.prefix, key.interfaceType));
    }

    private void initialLoad() {
        // 同步全量加载配置。如果资源不可用，这里采用快速失败策略阻断应用启动
        ConfigSnapshot snapshot = aggregator.aggregate(sources, System.nanoTime());
        snapshotRef.set(snapshot);
        log.info("Initial ConfigSnapshot built, version = {}, entries = {}", snapshot.getVersion(),
                snapshot.getEntries().size());
    }

    private void initWatchers() {
        if (CollUtil.isNotEmpty(watchers)) {
            for (ConfigWatcher watcher : watchers) {
                watcher.init();
                watcher.watch(hotReloadManager::signalChange);
            }
        }
    }

    @Override
    public ConfigSnapshot currentSnapshot() {
        return snapshotRef.get();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T createProxy(String prefix, Class<T> interfaceType) {
        return (T) proxyInstanceProvider.get(new ProxyKey(prefix, interfaceType));
    }

    private Object doCreateProxy(String prefix, Class<?> interfaceType) {
        // 如果是接口，返回 Live Mode 动态代理，支持实时热更新
        if (interfaceType.isInterface()) {
            return new ConfigProxyFactory().createLiveProxy(this, prefix, interfaceType);
        }

        if (configBinder == null) {
            throw new IllegalStateException("ConfigBinder is missing. Cannot create proxy.");
        }
        return configBinder.bind(currentSnapshot(), prefix, interfaceType);
    }

    @Override
    public void addChangeListener(String keyPattern, ConfigChangeListener listener) {
        if (StrUtil.isNotBlank(keyPattern) && listener != null) {
            listeners.add(new ListenerRegistration(keyPattern, listener));
        }
    }

    private void fireChangeEvents(HotReloadManager.ReloadEvent event) {
        if (listeners.isEmpty() || event.oldSnapshot == null || event.newSnapshot == null) {
            return;
        }

        Map<String, ConfigEntry> oldEntries = event.oldSnapshot.getEntries();
        Map<String, ConfigEntry> newEntries = event.newSnapshot.getEntries();

        // 收集所有涉及的 keys (Added, Modified, Deleted)
        Set<String> allKeys = new HashSet<>(oldEntries.keySet());
        allKeys.addAll(newEntries.keySet());

        for (String key : allKeys) {
            ConfigEntry oldNode = oldEntries.get(key);
            ConfigEntry newNode = newEntries.get(key);

            String oldVal = (oldNode == null || oldNode.isEmptyOrDeleted()) ? null : oldNode.getValue();
            String newVal = (newNode == null || newNode.isEmptyOrDeleted()) ? null : newNode.getValue();

            // 如果值确实发生变化（新增、删除、或者是值变动）
            if (!Objects.equals(oldVal, newVal)) {
                log.info("Config patch detected: key=[{}]", key);
                notifyListeners(key, oldVal, newVal);
            }
        }
    }

    private void notifyListeners(String changedKey, String oldVal, String newVal) {
        for (ListenerRegistration registration : listeners) {
            if (isMatch(registration.pattern, changedKey)) {
                try {
                    registration.listener.onChange(changedKey, oldVal, newVal);
                } catch (Exception e) {
                    log.error(e, "Error occurred while invoking ConfigChangeListener for key: {}", changedKey);
                }
            }
        }
    }

    /**
     * 简单的匹配验证机制
     */
    private boolean isMatch(String pattern, String key) {
        if (StrUtil.isBlank(pattern) || StrUtil.isBlank(key)) {
            return false;
        }
        if (pattern.endsWith("*")) {
            // 前缀匹配，例如: app.db.*
            String prefix = pattern.substring(0, pattern.length() - 1);
            return key.startsWith(prefix);
        }
        // 精确匹配
        return StrUtil.equals(pattern, key);
    }

    public void destroy() {
        hotReloadManager.destroy();
        if (CollUtil.isNotEmpty(watchers)) {
            for (ConfigWatcher watcher : watchers) {
                try {
                    watcher.destroy();
                } catch (Exception e) {
                    log.warn("Error destroying watcher: {}", e.getMessage());
                }
            }
        }
    }

    private static class ListenerRegistration {
        final String pattern;
        final ConfigChangeListener listener;

        ListenerRegistration(String pattern, ConfigChangeListener listener) {
            this.pattern = pattern;
            this.listener = listener;
        }
    }

    @EqualsAndHashCode
    private static class ProxyKey {
        private final String prefix;
        private final Class<?> interfaceType;

        private ProxyKey(String prefix, Class<?> interfaceType) {
            this.prefix = prefix;
            this.interfaceType = interfaceType;
        }
    }
}
