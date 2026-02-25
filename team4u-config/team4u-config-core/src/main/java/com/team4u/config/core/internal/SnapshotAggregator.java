package com.team4u.config.core.internal;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.team4u.config.core.ConfigChangeListener;
import com.team4u.config.core.domain.ConfigEntry;
import com.team4u.config.core.domain.ConfigSnapshot;
import com.team4u.config.core.spi.ConfigSource;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 负责收集排序所有数据源，生成并处理合并后的 ConfigSnapshot
 * 根据优先级顺序：值越小的优先级越高。高优先级覆盖低优先级。
 * 若高优先级产生 Tombstone 语义(value==null)，则代表已从高层覆盖删除，需要忽略底层同名键。
 */
public class SnapshotAggregator {

    /**
     * 将多个数据源的全量或者增量数据按优先级执行覆盖合并
     *
     * @param sources 实现了 OrderedPolicy 的各类配置源 (需已根据优先级排序)
     * @param version 最新快照需产生的版本号
     * @return 合并处理后的全新不可变快照
     */
    public ConfigSnapshot aggregate(List<ConfigSource> sources, long version) {
        if (CollUtil.isEmpty(sources)) {
            return new ConfigSnapshot(version, Collections.emptyMap());
        }

        // 以优先级升序排列
        List<ConfigSource> orderedSources = new ArrayList<>(sources);
        Collections.sort(orderedSources);

        Map<String, ConfigEntry> finalMap = new HashMap<>();

        for (ConfigSource source : orderedSources) {
            Map<String, ConfigEntry> loadedData;
            try {
                loadedData = source.load();
            } catch (Exception e) {
                // Initial Load 时可能直接抛出导致全站失败；由于框架定义，此异常可往上抛或记录
                throw new IllegalStateException("Failed to load config from source: " + source.name(), e);
            }

            if (MapUtil.isEmpty(loadedData)) {
                continue;
            }

            for (Map.Entry<String, ConfigEntry> entry : loadedData.entrySet()) {
                String key = entry.getKey();
                // 仅当高优先级中还没有该 Key 时，低优先级的才可以生效
                if (!finalMap.containsKey(key)) {
                    finalMap.put(key, entry.getValue());
                }
            }
        }

        return new ConfigSnapshot(version, finalMap);
    }
}
