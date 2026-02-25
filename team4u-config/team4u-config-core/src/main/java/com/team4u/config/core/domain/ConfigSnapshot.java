package com.team4u.config.core.domain;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import lombok.Getter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 核心配置快照 (不可变)
 * <p>
 * 所有的写操作都在构造时完成，一旦构建，全量数据不可变。
 * 基于 java.util.Collections.unmodifiableMap() 保证并发安全读写一致性。
 */
public class ConfigSnapshot {
    /**
     * 版本号 (通常为 System.nanoTime() 或自增 Sequence)
     */
    @Getter
    private final long version;
    /**
     * 底层不可变字典映射
     */
    @Getter
    private final Map<String, ConfigEntry> entries;

    public ConfigSnapshot(long version, Map<String, ConfigEntry> entries) {
        this.version = version;
        this.entries = MapUtil.isEmpty(entries) ? Collections.emptyMap() : Collections.unmodifiableMap(entries);
    }

    /**
     * O(1) 检索确切键的完整 ConfigEntry 对象
     *
     * @param key 配置键
     * @return 包含配置元数据的实体，如果不存在或已删除则返回 empty()
     */
    public Optional<ConfigEntry> getEntry(String key) {
        if (key == null) {
            return Optional.empty();
        }
        ConfigEntry entry = entries.get(key);
        if (entry != null && !entry.isEmptyOrDeleted()) {
            return Optional.of(entry);
        }
        // 如果值被显式删除，则视为未配置
        return Optional.empty();
    }

    /**
     * O(1) 读取字符串值
     *
     * @param key 配置键
     * @return 配置值
     */
    public Optional<String> get(String key) {
        return getEntry(key).map(ConfigEntry::getValue);
    }

    /**
     * 支持按前缀搜索检索嵌套配置，返回剥离前缀后的键值映射对。<p>
     * 例如前缀为 "app.db."，针对配置 "app.db.url" 将返回 "url" -> "jdbc..."。<p>
     * 入参前缀无论是否以 '.' 结尾，系统均会自动补齐匹配处理。
     *
     * @param prefix 配置前缀
     * @return 取消前缀的子配置映射；返回不可变集合，不会为 null
     */
    public Map<String, String> getByPrefix(String prefix) {
        if (CollUtil.isEmpty(entries) || StrUtil.isBlank(prefix)) {
            return Collections.emptyMap();
        }

        String searchPrefix = prefix.endsWith(".") ? prefix : prefix + ".";
        int prefixLen = searchPrefix.length();

        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, ConfigEntry> entry : entries.entrySet()) {
            String k = entry.getKey();
            ConfigEntry v = entry.getValue();

            if (k != null && k.startsWith(searchPrefix)) {
                if (!v.isEmptyOrDeleted()) {
                    String subKey = k.substring(prefixLen);
                    result.put(subKey, v.getValue());
                }
            }
        }
        return Collections.unmodifiableMap(result);
    }
}
