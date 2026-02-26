package com.team4u.framework.config.core.spi;

import com.team4u.framework.config.core.domain.ConfigEntry;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存的配置源实现
 * <p>
 * 将配置数据完全存储于 JVM 内存中，适用于以下场景：
 * <ul>
 *     <li>单元测试：快速构建隔离的测试配置环境</li>
 *     <li>运行时覆盖：动态注入或手动覆盖现有的配置项</li>
 *     <li>静态默认值：提供系统基础的兜底配置</li>
 * </ul>
 * 本实现同时集成了 {@link ConfigWatcher} 能力。通过 {@code putAndRefresh} 等方法更新配置后，
 * 会立即发送变更信号，触发配置中心的快照重载。
 * 本类是线程安全的，并完整支持 {@link ConfigSource#loadSince(long)} 增量加载协议。
 * </p>
 */
public class InMemoryConfigSource implements ConfigSource, ConfigWatcher {

    /**
     * 数据源描述名称
     */
    private final String name;

    /**
     * 排序优先级
     */
    private final int priority;

    /**
     * 内部线程安全的存储容器
     */
    private final ConcurrentHashMap<String, ConfigEntry> store = new ConcurrentHashMap<>();

    /**
     * 由配置中心注入的变更信号回调
     */
    private volatile Runnable changeSignal;

    /**
     * 构建内存配置源
     *
     * @param name     描述名称，例如 "defaults"
     * @param priority 优先级数值，数值越小越优先
     */
    public InMemoryConfigSource(String name, int priority) {
        this.name = name;
        this.priority = priority;
    }

    /**
     * 写入或更新配置项
     * <p>
     * 每次写入都会更新时间戳，以支持增量加载检索。
     * </p>
     *
     * @param key   配置键
     * @param value 配置值；传入 {@link ConfigSource#TOMBSTONE_VALUE} 表示标记该键为失效
     */
    public void put(String key, String value) {
        store.put(key, new ConfigEntry(key, value, name, System.currentTimeMillis()));
    }

    /**
     * 批量写入配置项
     */
    public void putAll(Map<String, String> entries) {
        entries.forEach(this::put);
    }

    /**
     * 写入配置并立即刷新快照
     */
    public void putAndRefresh(String key, String value) {
        put(key, value);
        fireChange();
    }

    /**
     * 批量写入配置并立即刷新快照
     */
    public void putAllAndRefresh(Map<String, String> entries) {
        putAll(entries);
        fireChange();
    }

    /**
     * 发送配置变更信号，触发全局快照重载
     */
    public void fireChange() {
        if (changeSignal != null) {
            changeSignal.run();
        }
    }

    /**
     * 标记指定配置键为失效（Tombstone）
     * <p>
     * 在聚合时，此标记将屏蔽低优先级源中的同名配置。
     * </p>
     */
    public void delete(String key) {
        put(key, ConfigSource.TOMBSTONE_VALUE);
    }

    /**
     * 物理移除配置项
     * <p>
     * 彻底从内存存储中移除该键。与 {@link #delete} 不同，物理移除后，低优先级源中的同名配置将重新生效。
     * </p>
     */
    public void remove(String key) {
        store.remove(key);
    }

    /**
     * 清空所有配置项
     */
    public void clear() {
        store.clear();
    }

    /**
     * 获取当前存储的配置项总数
     */
    public int size() {
        return store.size();
    }

    // -----------------------------------------------------------------------
    // ConfigWatcher 接口实现
    // -----------------------------------------------------------------------

    @Override
    public void watch(Runnable changeSignal) {
        this.changeSignal = changeSignal;
    }

    // -----------------------------------------------------------------------
    // ConfigSource 接口实现
    // -----------------------------------------------------------------------

    @Override
    public String name() {
        return name;
    }

    @Override
    public int priority() {
        return priority;
    }

    /**
     * 获取全量配置镜像
     */
    @Override
    public Map<String, ConfigEntry> load() {
        return Collections.unmodifiableMap(new HashMap<>(store));
    }

    /**
     * 执行增量加载
     * <p>
     * 返回所有更新时间戳晚于指定时间点的配置条目。
     * </p>
     */
    @Override
    public Map<String, ConfigEntry> loadSince(long timestamp) {
        Map<String, ConfigEntry> changed = new HashMap<>();
        for (ConfigEntry entry : store.values()) {
            if (entry.getTimestamp() > timestamp) {
                changed.put(entry.getKey(), entry);
            }
        }
        return Collections.unmodifiableMap(changed);
    }
}
