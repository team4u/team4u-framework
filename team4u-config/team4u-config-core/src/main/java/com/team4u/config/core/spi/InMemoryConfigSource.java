package com.team4u.config.core.spi;

import com.team4u.config.core.domain.ConfigEntry;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存的配置源实现
 * <p>
 * 将配置数据完全保存在 JVM 堆内存中，适合以下场景：
 * <ul>
 * <li>单元测试：无需依赖外部数据库或文件，快速构造测试数据</li>
 * <li>动态覆盖：在运行时临时写入或覆盖来自其他数据源的配置项</li>
 * <li>默认值：作为兜底配置源，为系统提供静态默认值</li>
 * </ul>
 * 本实现同时具备 {@link ConfigWatcher} 能力：
 * 通过 {@link #putAndRefresh(String, String)} 等方法写入配置后，
 * 会自动触发变更信号通知 ConfigManager 重新聚合快照。
 * <p>
 * 本实现线程安全，且完整支持 {@link ConfigSource#loadSince(long)} 增量加载逻辑。
 */
public class InMemoryConfigSource implements ConfigSource, ConfigWatcher {

    /**
     * 数据源名称
     */
    private final String name;

    /**
     * 数据源优先级，数值越小优先级越高
     */
    private final int priority;

    /**
     * 内部存储，key 为配置键，value 为配置条目（含时间戳）
     * 使用 ConcurrentHashMap 保证并发写入安全
     */
    private final ConcurrentHashMap<String, ConfigEntry> store = new ConcurrentHashMap<>();

    /**
     * 变更信号回调，由 ConfigManager 在启动阶段通过 {@link #watch(Runnable)} 注入。
     * 调用此回调将通知 HotReloadManager 触发配置快照的重新聚合。
     */
    private volatile Runnable changeSignal;

    /**
     * 通过名称和优先级构建内存配置源
     *
     * @param name     数据源名称，如 "defaults" 或 "override"
     * @param priority 排序优先级，数值越小越优先（参见 {@link ConfigSource#priority()}）
     */
    public InMemoryConfigSource(String name, int priority) {
        this.name = name;
        this.priority = priority;
    }

    /**
     * 写入或更新一个配置项
     * <p>
     * 时间戳取写入时刻的系统毫秒数，用于支持增量加载。
     *
     * @param key   配置键
     * @param value 配置值，传入 {@link ConfigSource#TOMBSTONE_VALUE} 等同于调用
     *              {@link #delete(String)}，表示将该配置标记为删除（Tombstone）
     */
    public void put(String key, String value) {
        store.put(key, new ConfigEntry(key, value, name, System.currentTimeMillis()));
    }

    /**
     * 将一批配置项一次性写入内存源
     * <p>
     * 逐条调用 {@link #put(String, String)}，每条记录独立记录写入时间戳。
     *
     * @param entries 键值对映射，值为 {@link ConfigSource#TOMBSTONE_VALUE}
     *                时视为删除标记（Tombstone）
     */
    public void putAll(Map<String, String> entries) {
        entries.forEach(this::put);
    }

    /**
     * 写入配置项并触发变更信号
     * <p>
     * 等价于先调用 {@link #put(String, String)}，再通知 ConfigManager 重新聚合快照。
     * 适用于写入后需要立即生效的场景。
     *
     * @param key   配置键
     * @param value 配置值，传入 {@link ConfigSource#TOMBSTONE_VALUE} 表示标记为删除（Tombstone）
     */
    public void putAndRefresh(String key, String value) {
        put(key, value);
        fireChange();
    }

    /**
     * 批量写入配置项并触发变更信号
     * <p>
     * 等价于先调用 {@link #putAll(Map)}，再统一发出一次变更信号。
     * 适用于需要一次性写入多条配置后立刻生效的场景。
     *
     * @param entries 键值对映射，值为 {@link ConfigSource#TOMBSTONE_VALUE}
     *                时视为删除标记（Tombstone）
     */
    public void putAllAndRefresh(Map<String, String> entries) {
        putAll(entries);
        fireChange();
    }

    /**
     * 手动触发变更信号
     * <p>
     * 当使用 {@link #put} 或 {@link #putAll} 进行批量写入后，
     * 可在合适的时机调用此方法统一触发一次配置重载，避免频繁刷新。
     */
    public void fireChange() {
        if (changeSignal != null) {
            changeSignal.run();
        }
    }

    /**
     * 将指定配置键标记为已删除（Tombstone）
     * <p>
     * 向聚合层发出删除信号，聚合时会屏蔽低优先级数据源中相同键的值。
     *
     * @param key 待删除的配置键
     */
    public void delete(String key) {
        put(key, ConfigSource.TOMBSTONE_VALUE);
    }

    /**
     * 从内存中彻底移除某个配置键（含 Tombstone）
     * <p>
     * 与 {@link #delete(String)} 不同，此方法直接从存储中抹去该键的痕迹，
     * 不会向聚合层发送删除信号，低优先级数据源中的同名键将重新生效。
     *
     * @param key 待移除的配置键
     */
    public void remove(String key) {
        store.remove(key);
    }

    /**
     * 清空所有内存配置项
     */
    public void clear() {
        store.clear();
    }

    /**
     * 返回当前内存中的配置项数量（含 Tombstone 条目）
     *
     * @return 配置项总数
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
     * 返回当前内存中全部配置项的只读快照
     *
     * @return 不可变的配置键值映射
     */
    @Override
    public Map<String, ConfigEntry> load() {
        return Collections.unmodifiableMap(new HashMap<>(store));
    }

    /**
     * 增量加载：返回在指定时间戳之后发生变更的配置项
     * <p>
     * 遍历内存存储，筛选出 {@link ConfigEntry#getTimestamp()} 严格大于 {@code timestamp} 的条目，
     * 从而避免将未变更的配置重复传递给聚合层。
     *
     * @param timestamp 上次加载的时间戳（毫秒）
     * @return 在该时间戳之后被写入或更新的配置项，若无变更则返回空 Map
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
