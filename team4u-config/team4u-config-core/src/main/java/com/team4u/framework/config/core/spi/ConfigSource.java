package com.team4u.framework.config.core.spi;

import com.team4u.framework.config.core.domain.ConfigEntry;
import com.team4u.framework.policy.OrderedPolicy;

import java.util.Map;

/**
 * 核心配置数据源
 * <p>
 * 负责加载数据。复用 {@link OrderedPolicy} 接口，从而天然具备基于 {@code priority()} 的排序和策略加载能力。
 */
public interface ConfigSource extends OrderedPolicy {

    /**
     * 表示配置项已被删除或未定义的哨兵值（Tombstone）。
     * <p>
     * 当一个配置源返回此值时，表示它显式屏蔽了低优先级数据源中的同名配置项。
     */
    String TOMBSTONE_VALUE = null;

    /**
     * 数据源名称，用于标识配置的来源环境或渠道。
     * 例如："JDBC-Primary", "File:/opt/conf/app.prop" 等
     *
     * @return 明确的唯一名称
     */
    String name();

    /**
     * 核心全量加载逻辑
     *
     * @return 当前源的所有配置，包含被标记为删除的 Tombstone 数据(即 value 为 {@link #TOMBSTONE_VALUE} 的
     * ConfigEntry)
     */
    Map<String, ConfigEntry> load();

    /**
     * 增量加载优化 (可选实现)，返回 null 表示当前此源不支持增量加载，需要回退到全量 load()
     *
     * @param timestamp 上次访问时间戳
     * @return 变更的配置项
     */
    default Map<String, ConfigEntry> loadSince(long timestamp) {
        return null;
    }
}
