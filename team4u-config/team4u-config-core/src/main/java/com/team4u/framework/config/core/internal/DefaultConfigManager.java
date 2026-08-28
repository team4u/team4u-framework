package com.team4u.framework.config.core.internal;

import com.team4u.framework.base.instance.DynamicInstanceProvider;
import com.team4u.framework.base.util.CollectionUtil;
import com.team4u.framework.base.util.StringUtil;
import com.team4u.framework.config.core.ConfigChangeListener;
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.ConfigProxyContext;
import com.team4u.framework.config.core.ConfigProxyCreator;
import com.team4u.framework.config.core.annotation.ConfigPrefix;
import com.team4u.framework.config.core.convert.PropertyConverterRegistry;
import com.team4u.framework.config.core.domain.ConfigEntry;
import com.team4u.framework.config.core.domain.ConfigSnapshot;
import com.team4u.framework.config.core.spi.ConfigSourceRegistry;
import com.team4u.framework.config.core.spi.ConfigWatcher;
import com.team4u.framework.config.core.spi.ConfigWatcherRegistry;
import lombok.EqualsAndHashCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 配置中心默认实现类
 * <p>
 * 该类作为配置系统的核心调度引擎，负责协调配置源加载、动态代理创建、热更新维护以及变更事件分发。
 * </p>
 */
public class DefaultConfigManager implements ConfigManager {

    private static final Logger log = LoggerFactory.getLogger(DefaultConfigManager.class);
    private static final String PROXY_PROVIDER_UNAVAILABLE_MESSAGE =
            "Config proxy provider is unavailable: add com.team4u:team4u-config-proxy "
                    + "or provide a ConfigProxyCreator implementation.";
    private static volatile DefaultConfigManager global;
    /**
     * 当前生效的配置快照引用
     */
    private final AtomicReference<ConfigSnapshot> snapshotRef = new AtomicReference<>();
    /**
     * 注册表引用
     */
    private final ConfigSourceRegistry sourceRegistry;
    private final ConfigWatcherRegistry watcherRegistry;
    private final ConfigProxyCreator proxyCreator;
    private final ConfigProxyContext proxyContext;
    /**
     * 聚合器，负责合并各配置源数据
     */
    private final SnapshotAggregator aggregator = new SnapshotAggregator();
    /**
     * 热加载管理器，处理变更防抖与异步更新
     */
    private final HotReloadManager hotReloadManager;
    private final AtomicLong versionGenerator = new AtomicLong(System.currentTimeMillis());
    /**
     * 用户注册的配置变更监听器容器
     */
    private final List<ListenerRegistration> listeners = new CopyOnWriteArrayList<>();
    /**
     * 代理对象实例缓存，避免重复创建代理实例
     */
    private final DynamicInstanceProvider<ProxyKey, ProxyKey, Object> proxyInstanceProvider;
    private final Set<ConfigWatcher> activeWatchers = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Object lifecycleMonitor = new Object();
    private final Object watcherLifecycleMonitor = new Object();
    /**
     * @param sourceRegistry     配置源注册表
     * @param watcherRegistry    配置监听器注册表
     * @param converterRegistry  属性转换器注册表
     * @param proxyCreator       代理创建器；可为 null，表示未提供代理能力
     * @param debounceWindowMs   防抖延迟时间（毫秒）。传入 0 或负数时同步立即执行重载，
     *                           可用于单元测试环境消除等待。
     */
    public DefaultConfigManager(ConfigSourceRegistry sourceRegistry,
                                ConfigWatcherRegistry watcherRegistry,
                                PropertyConverterRegistry converterRegistry,
                                ConfigProxyCreator proxyCreator,
                                long debounceWindowMs) {
        this.sourceRegistry = sourceRegistry;
        this.watcherRegistry = watcherRegistry;
        this.proxyCreator = proxyCreator;
        this.proxyContext = new DefaultConfigProxyContext(this, converterRegistry);

        // 执行初始化的同步配置加载
        initialLoad();

        // 配置热重载管理器，防抖窗口由外部传入，支持测试环境配置为 0 以实现同步重载
        this.hotReloadManager = new HotReloadManager(
                this.sourceRegistry::getPolicies,
                this.aggregator,
                this.versionGenerator,
                debounceWindowMs,
                this::commitReload,
                this::fireChangeEvents);
        // 启动各配置源的监控任务
        reconcileWatchers();

        // 基于 LRU 策略初始化代理对象缓存提供者
        this.proxyInstanceProvider = DynamicInstanceProvider.createLru(
                1024,
                key -> key,
                key -> doCreateProxy(key.prefix, key.configType));
    }

    /**
     * 获取全局标准单例配置管理引擎
     */
    public static DefaultConfigManager global() {
        // ConfigManager.class serializes global initialization, refresh, and reset.
        synchronized (ConfigManager.class) {
            if (global == null) {
                ConfigManager globalManager = Builder.buildGlobal();
                if (!(globalManager instanceof DefaultConfigManager)) {
                    throw new IllegalStateException(
                            "Config global manager must be DefaultConfigManager: "
                                    + globalManager.getClass().getName());
                }
                global = (DefaultConfigManager) globalManager;
            }
            return global;
        }
    }

    public static void refreshGlobalIfInitialized() {
        // ConfigManager.class serializes global initialization, refresh, and reset.
        synchronized (ConfigManager.class) {
            DefaultConfigManager current = global;
            if (current != null) {
                current.refresh();
            }
        }
    }

    public static DefaultConfigManager globalOrNullForTests() {
        synchronized (ConfigManager.class) {
            return global;
        }
    }
    /**
     * Discards the global manager reference without initializing an absent manager.
     * Test cleanup must destroy the manager first through ConfigBootstrap.resetForTests().
     */
    public static void discardGlobalForTests() {
        // Callers must reset bootstrap first; this only removes an already-destroyed manager.
        synchronized (ConfigManager.class) {
            global = null;
        }
    }

    /**
     * 刷新配置
     * <p>
     * 强制重新加载配置源并重新初始化监听器。
     * </p>
     */
    public void refresh() {
        synchronized (lifecycleMonitor) {
            hotReloadManager.resumeAcceptingReloads();
            log.info("Refreshing configuration...");
            initialLoad();
            reconcileWatchers();
        }
    }

    private HotReloadManager.ReloadEvent commitReload(long generation, ConfigSnapshot newSnapshot) {
        synchronized (lifecycleMonitor) {
            if (!hotReloadManager.isReloadCurrent(generation)) {
                return null;
            }
            ConfigSnapshot oldSnapshot = snapshotRef.getAndSet(newSnapshot);
            return new HotReloadManager.ReloadEvent(oldSnapshot, newSnapshot);
        }
    }

    /**
     * 同步加载全量配置
     * <p>
     * 采用快速失败策略。如果配置源不可达或加载失败，将阻断应用启动以确保系统的配置确定性。
     * </p>
     */
    private void initialLoad() {
        ConfigSnapshot snapshot = aggregator.aggregate(sourceRegistry.getPolicies(), nextVersion());
        snapshotRef.set(snapshot);
        log.info("ConfigSnapshot built, version = {}, entries = {}", snapshot.getVersion(),
                snapshot.getEntries().size());
    }

    /**
     * 启动各配置监听器实现
     */
    private long nextVersion() {
        return versionGenerator.incrementAndGet();
    }

    /**
     * 启动或回收监听器，确保 refresh() 不会重复拉起已运行的 watcher。
     */
    private void reconcileWatchers() {
        synchronized (watcherLifecycleMonitor) {
            Set<ConfigWatcher> desiredWatchers = Collections.newSetFromMap(new IdentityHashMap<>());
            List<ConfigWatcher> watchers = watcherRegistry.getPolicies();
            if (CollectionUtil.isNotEmpty(watchers)) {
                desiredWatchers.addAll(watchers);
            }

            for (ConfigWatcher activeWatcher : new ArrayList<>(activeWatchers)) {
                if (!desiredWatchers.contains(activeWatcher)) {
                    destroyWatcher(activeWatcher);
                    activeWatchers.remove(activeWatcher);
                }
            }

            for (ConfigWatcher watcher : desiredWatchers) {
                if (activeWatchers.contains(watcher)) {
                    continue;
                }
                watcher.init();
                watcher.watch(hotReloadManager::signalChange);
                activeWatchers.add(watcher);
            }
        }
    }

    @Override
    public ConfigSnapshot currentSnapshot() {
        return snapshotRef.get();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T createProxy(String prefix, Class<T> configType) {
        String finalPrefix = resolveProxyPrefix(prefix, configType);
        if (proxyCreator == null) {
            throw new IllegalStateException(PROXY_PROVIDER_UNAVAILABLE_MESSAGE);
        }
        return (T) proxyInstanceProvider.get(new ProxyKey(finalPrefix, configType));
    }

    private String resolveProxyPrefix(String prefix, Class<?> configType) {
        ConfigPrefix annotation = configType.getAnnotation(ConfigPrefix.class);
        if (annotation == null) {
            return prefix == null ? "" : prefix;
        }

        String classPrefix = annotation.value();
        if (StringUtil.isBlank(prefix)) {
            return classPrefix == null ? "" : classPrefix;
        }
        return prefix + "." + classPrefix;
    }

    private Object doCreateProxy(String prefix, Class<?> configType) {
        Object proxy = proxyCreator.create(proxyContext, prefix, configType);
        if (proxy == null) {
            throw new IllegalStateException("ConfigProxyCreator returned null: prefix=[" + prefix
                    + "], configType=[" + configType.getName() + "]");
        }
        return proxy;
    }

    @Override
    public AutoCloseable registerChangeListener(String keyPattern, ConfigChangeListener listener) {
        if (StringUtil.isBlank(keyPattern) || listener == null) {
            return () -> {
            };
        }

        ListenerRegistration registration = new ListenerRegistration(keyPattern, listener);
        listeners.add(registration);
        return () -> listeners.remove(registration);
    }

    /**
     * 防抖延迟时间（毫秒）
     */
    public void setDebounceWindowMs(long debounceWindowMs) {
        this.hotReloadManager.setDebounceWindowMs(debounceWindowMs);
    }

    /**
     * 分发配置变更事件
     * <p>
     * 对比新旧快照差异，识别新增、修改或失效的配置项，并触发对应的监听器。
     * </p>
     */
    private void fireChangeEvents(HotReloadManager.ReloadEvent event) {
        if (listeners.isEmpty() || event.oldSnapshot == null || event.newSnapshot == null) {
            return;
        }

        Map<String, ConfigEntry> oldEntries = event.oldSnapshot.getEntries();
        Map<String, ConfigEntry> newEntries = event.newSnapshot.getEntries();

        Set<String> allKeys = new HashSet<>(oldEntries.keySet());
        allKeys.addAll(newEntries.keySet());

        for (String key : allKeys) {
            ConfigEntry oldNode = oldEntries.get(key);
            ConfigEntry newNode = newEntries.get(key);

            String oldVal = (oldNode == null || oldNode.isEmptyOrDeleted()) ? null : oldNode.getValue();
            String newVal = (newNode == null || newNode.isEmptyOrDeleted()) ? null : newNode.getValue();

            // 检测值内容是否确实发生了变化
            if (!Objects.equals(oldVal, newVal)) {
                log.info("Config patch detected: key=[{}]", key);
                notifyListeners(key, oldVal, newVal);
            }
        }
    }

    /**
     * 通知匹配的监听器
     */
    private void notifyListeners(String changedKey, String oldVal, String newVal) {
        for (ListenerRegistration registration : listeners) {
            if (isMatch(registration.pattern, changedKey)) {
                try {
                    registration.listener.onChange(changedKey, oldVal, newVal);
                } catch (Exception e) {
                    log.error("Error occurred while invoking ConfigChangeListener for key: {}", changedKey, e);
                }
            }
        }
    }

    /**
     * 执行配置键模式匹配逻辑
     * <p>
     * 支持通配符匹配（以 * 结尾的前缀模式）以及严格的相等匹配。
     * </p>
     */
    private boolean isMatch(String pattern, String key) {
        if (StringUtil.isBlank(pattern) || StringUtil.isBlank(key)) {
            return false;
        }
        if (pattern.endsWith("*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return key.startsWith(prefix);
        }
        return StringUtil.equals(pattern, key);
    }

    /**
     * 销毁实例，释放线程池与监听器资源
     */
    public void destroy() {
        synchronized (lifecycleMonitor) {
            destroyActiveWatchers();
            hotReloadManager.destroy();
        }
    }
    private void destroyActiveWatchers() {
        synchronized (watcherLifecycleMonitor) {
            for (ConfigWatcher watcher : new ArrayList<>(activeWatchers)) {
                destroyWatcher(watcher);
            }
            activeWatchers.clear();
        }
    }

    /**
     * 仅用于测试场景，重置运行时状态但保留全局单例对象本身。
     */
    public void resetForTests() {
        synchronized (lifecycleMonitor) {
            destroyActiveWatchers();
            hotReloadManager.cancelPendingReload();
            listeners.clear();
            proxyInstanceProvider.clear();
            snapshotRef.set(new ConfigSnapshot(nextVersion(), Collections.emptyMap()));
        }
    }

    private void destroyWatcher(ConfigWatcher watcher) {
        try {
            watcher.destroy();
        } catch (Exception e) {
            log.warn("Error destroying watcher: {}", e.getMessage());
        }
    }

    /**
     * 监听器注册项封装类
     */
    private static class ListenerRegistration {
        final String pattern;
        final ConfigChangeListener listener;

        ListenerRegistration(String pattern, ConfigChangeListener listener) {
            this.pattern = pattern;
            this.listener = listener;
        }
    }

    private static class DefaultConfigProxyContext implements ConfigProxyContext {
        private final DefaultConfigManager manager;
        private final PropertyConverterRegistry converterRegistry;

        private DefaultConfigProxyContext(DefaultConfigManager manager,
                                         PropertyConverterRegistry converterRegistry) {
            this.manager = manager;
            this.converterRegistry = converterRegistry;
        }

        @Override
        public ConfigManager manager() {
            return manager;
        }

        @Override
        public PropertyConverterRegistry converterRegistry() {
            return converterRegistry;
        }
    }

    /**
     * 代理缓存键
     */
    @EqualsAndHashCode
    private static class ProxyKey {
        private final String prefix;
        private final Class<?> configType;

        private ProxyKey(String prefix, Class<?> configType) {
            this.prefix = prefix;
            this.configType = configType;
        }
    }
}
