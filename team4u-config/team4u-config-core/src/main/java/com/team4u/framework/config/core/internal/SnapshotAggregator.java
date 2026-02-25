package com.team4u.framework.config.core.internal;

import cn.hutool.core.collection.CollUtil;
import com.team4u.framework.config.core.domain.ConfigEntry;
import com.team4u.framework.config.core.domain.ConfigSnapshot;
import com.team4u.framework.config.core.spi.ConfigSource;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 负责收集所有配置数据源，生成并处理合并后的 ConfigSnapshot
 * <p>
 * 根据优先级顺序：高优先级在前，低优先级在后。
 * 若高优先级产生 Tombstone 语义 (value==null)，则代表已从高层覆盖删除，需要忽略底层同名键。
 */
public class SnapshotAggregator {

    /**
     * 将多个数据源的全量或者增量数据按优先级执行覆盖合并
     *
     * @param sources 实现了 OrderedPolicy 的各类配置源 (需已根据优先级排序，高优先级在前)
     * @param version 最新快照需产生的版本号
     * @return 合并处理后的全新不可变快照
     */
    public ConfigSnapshot aggregate(List<ConfigSource> sources, long version) {
        if (CollUtil.isEmpty(sources)) {
            return new ConfigSnapshot(version, Collections.emptyMap());
        }

        Map<String, ConfigEntry> finalMap = new HashMap<>();

        for (ConfigSource source : sources) {
            loadSource(source).forEach(finalMap::putIfAbsent);
        }

        return new ConfigSnapshot(version, finalMap);
    }

    private Map<String, ConfigEntry> loadSource(ConfigSource source) {
        try {
            Map<String, ConfigEntry> data = source.load();
            return data != null ? data : Collections.emptyMap();
        } catch (Exception e) {
            // Initial Load 时可能直接抛出导致全站失败；由于框架定义，此异常可往上抛或记录
            throw new IllegalStateException("Failed to load config from source: " + source.name(), e);
        }
    }
}
