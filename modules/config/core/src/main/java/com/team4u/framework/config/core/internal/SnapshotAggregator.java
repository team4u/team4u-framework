package com.team4u.framework.config.core.internal;

import com.team4u.framework.base.util.CollectionUtil;
import com.team4u.framework.config.core.domain.ConfigEntry;
import com.team4u.framework.config.core.domain.ConfigSnapshot;
import com.team4u.framework.config.core.spi.ConfigSource;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置快照聚合器
 * <p>
 * 负责收集所有已注册的数据源，并按照优先级顺序执行覆盖合并，最终生成一份全新的不可变快照。
 * 覆盖规则：
 * <ul>
 *     <li>高优先级配置源在前，低优先级在后</li>
 *     <li>对于相同的配置键，高优先级源的值将屏蔽低优先级源的值</li>
 *     <li>若高优先级源返回失效标记（Tombstone），则该配置项在最终结果中将被移除，不会回退到低优先级源的值</li>
 * </ul>
 * </p>
 */
public class SnapshotAggregator {

    /**
     * 将多个数据源的数据按优先级执行覆盖合并，生成新快照
     *
     * @param sources 实现了 OrderedPolicy 接口的配置源列表（需预先按优先级升序排列，即数值越小优先级越高）
     * @param version 待生成快照的版本标识
     * @return 聚合处理后的全新不可变快照对象
     */
    public ConfigSnapshot aggregate(List<ConfigSource> sources, long version) {
        if (CollectionUtil.isEmpty(sources)) {
            return new ConfigSnapshot(version, Collections.emptyMap());
        }

        Map<String, ConfigEntry> finalMap = new HashMap<>();

        for (ConfigSource source : sources) {
            // putIfAbsent 确保只有在当前没有该键时才放入，由于 sources 已按优先级排序，
            // 循环执行 putIfAbsent 自然实现了高优先级覆盖低优先级的语义。
            loadSource(source).forEach(finalMap::putIfAbsent);
        }

        return new ConfigSnapshot(version, finalMap);
    }

    /**
     * 执行单个配置源的数据加载任务
     */
    private Map<String, ConfigEntry> loadSource(ConfigSource source) {
        try {
            Map<String, ConfigEntry> data = source.load();
            return data != null ? data : Collections.emptyMap();
        } catch (Exception e) {
            // 若加载过程发生异常，将阻断聚合过程以防止产生不确定的配置结果
            throw new IllegalStateException("Failed to load config from source: " + source.name(), e);
        }
    }
}
