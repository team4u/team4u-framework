package com.team4u.framework.config.core.internal;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import com.team4u.framework.base.instance.DynamicInstanceProvider;
import com.team4u.framework.config.core.ConfigChangeListener;
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.annotation.ConfigPrefix;
import com.team4u.framework.config.core.convert.PropertyConverterRegistry;
import com.team4u.framework.config.core.domain.ConfigEntry;
import com.team4u.framework.config.core.domain.ConfigSnapshot;
import com.team4u.framework.config.core.proxy.ConfigProxyFactory;
import com.team4u.framework.config.core.spi.*;
import lombok.EqualsAndHashCode;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 配置中心默认实现类
 * <p>
 * 该类作为配置系统的核心调度引擎，负责协调配置源加载、动态代理创建、热更新维护以及变更事件分发。
 * </p>
 */
public class DefaultConfigManager implements ConfigManager {

    private static final Log log = LogFactory.get();

    /**
     * 当前生效的配置快照引用
     */
    private final AtomicReference<ConfigSnapshot> snapshotRef = new AtomicReference<>();
    /**
     * 已注册的配置源列表，按优先级排序
     */
    private final List<ConfigSource> sources;
    /**
     * 已注册的变更监听器列表
     */
    private final List<ConfigWatcher> watchers;
    /**
     * 对象绑定器
     */
    private final ConfigBinder configBinder;
    /**
     * 代理工厂
     */
    private final ConfigProxyFactory proxyFactory;

    /**
     * 聚合器，负责合并各配置源数据
     */
    private final SnapshotAggregator aggregator = new SnapshotAggregator();
    /**
     * 热加载管理器，处理变更防抖与异步更新
     */
    private final HotReloadManager hotReloadManager;

    /**
     * 用户注册的配置变更监听器容器
     */
    private final List<ListenerRegistration> listeners = new CopyOnWriteArrayList<>();

    /**
     * 代理对象实例缓存，避免重复创建代理实例
     */
    private final DynamicInstanceProvider<ProxyKey, ProxyKey, Object> proxyInstanceProvider;

    public DefaultConfigManager(ConfigSourceRegistry sourceRegistry,
                                ConfigWatcherRegistry watcherRegistry,
                                PropertyConverterRegistry converterRegistry,
                                ConfigBinder configBinder) {
        this.sources = sourceRegistry.getPolicies();
        this.watchers = watcherRegistry.getPolicies();
        this.configBinder = configBinder;
        this.proxyFactory = new ConfigProxyFactory(converterRegistry);

        // 执行初始化的同步配置加载
        initialLoad();

        // 配置热重载管理器，并设置 500ms 的防抖窗口以应对密集变更
        this.hotReloadManager = new HotReloadManager(
                snapshotRef,
                this.sources,
                this.aggregator,
                500,
                this::fireChangeEvents);

        // 启动各配置源的监控任务
        initWatchers();

        // 基于 LRU 策略初始化代理对象缓存提供者
        this.proxyInstanceProvider = DynamicInstanceProvider.createLru(
                1024,
                key -> key,
                key -> doCreateProxy(key.prefix, key.interfaceType));
    }

    /**
     * 同步加载全量配置
     * <p>
     * 采用快速失败策略。如果配置源不可达或加载失败，将阻断应用启动以确保系统的配置确定性。
     * </p>
     */
    private void initialLoad() {
        ConfigSnapshot snapshot = aggregator.aggregate(sources, System.nanoTime());
        snapshotRef.set(snapshot);
        log.info("Initial ConfigSnapshot built, version = {}, entries = {}", snapshot.getVersion(),
                snapshot.getEntries().size());
    }

    /**
     * 启动各配置监听器实现
     */
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

    /**
     * 执行实际的代理或绑定操作
     * <p>
     * 处理逻辑如下：
     * <ul>
     *     <li>识别接口上的配置前缀注解并进行路径叠加</li>
     *     <li>若目标为接口，则返回支持热更新的动态代理对象</li>
     *     <li>若目标为普通类，则调用绑定器将配置映射到 Bean 属性中</li>
     * </ul>
     * </p>
     */
    private Object doCreateProxy(String prefix, Class<?> interfaceType) {
        String finalPrefix = prefix;

        ConfigPrefix annotation = interfaceType.getAnnotation(ConfigPrefix.class);
        if (annotation != null) {
            String classPrefix = annotation.value();
            if (StrUtil.isBlank(finalPrefix)) {
                finalPrefix = classPrefix;
            } else {
                finalPrefix = finalPrefix + "." + classPrefix;
            }
        }

        if (finalPrefix == null) {
            finalPrefix = "";
        }

        if (interfaceType.isInterface()) {
            return proxyFactory.createLiveProxy(this, finalPrefix, interfaceType);
        }

        if (configBinder == null) {
            throw new IllegalStateException("ConfigBinder is missing. Cannot create proxy.");
        }
        return configBinder.bind(currentSnapshot(), finalPrefix, interfaceType);
    }

    @Override
    public void addChangeListener(String keyPattern, ConfigChangeListener listener) {
        if (StrUtil.isNotBlank(keyPattern) && listener != null) {
            listeners.add(new ListenerRegistration(keyPattern, listener));
        }
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
                    log.error(e, "Error occurred while invoking ConfigChangeListener for key: {}", changedKey);
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
        if (StrUtil.isBlank(pattern) || StrUtil.isBlank(key)) {
            return false;
        }
        if (pattern.endsWith("*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return key.startsWith(prefix);
        }
        return StrUtil.equals(pattern, key);
    }

    /**
     * 销毁实例，释放线程池与监听器资源
     */
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

    /**
     * 代理缓存键
     */
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
