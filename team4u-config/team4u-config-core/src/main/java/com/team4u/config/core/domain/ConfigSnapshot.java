package com.team4u.config.core.domain;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.team4u.config.core.internal.PlaceholderResolver;
import lombok.Getter;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    /**
     * 结构化视图缓存
     */
    private volatile Map<String, Object> unflattenedMap;

    public ConfigSnapshot(long version, Map<String, ConfigEntry> entries) {
        this.version = version;
        this.entries = MapUtil.isEmpty(entries) ? Collections.emptyMap() : Collections.unmodifiableMap(entries);
    }

    /**
     * 获取全量结构化配置 Map（延迟加载并缓存）
     *
     * @return 嵌套的 Map 结构
     */
    public Map<String, Object> unflattenedMap() {
        if (unflattenedMap == null) {
            synchronized (this) {
                if (unflattenedMap == null) {
                    unflattenedMap = buildUnflattenedMap();
                }
            }
        }
        return unflattenedMap;
    }

    private Map<String, Object> buildUnflattenedMap() {
        if (MapUtil.isEmpty(entries)) {
            return Collections.emptyMap();
        }

        Map<String, Object> root = new LinkedHashMap<>();
        // 复用 HashSet 以减少在高频调用下的对象分配
        Set<String> visitedKeys = new HashSet<>();

        for (Map.Entry<String, ConfigEntry> entry : entries.entrySet()) {
            String key = entry.getKey();
            ConfigEntry configEntry = entry.getValue();

            if (key == null || configEntry.isEmptyOrDeleted()) {
                continue;
            }

            // 解析占位符，预先解析可以显著提升后续 bind 的性能
            String resolvedValue = PlaceholderResolver.resolve(configEntry.getValue(), this, visitedKeys);

            // 填充树形结构
            int start = 0;
            int dotIndex;
            Map<String, Object> current = root;

            while ((dotIndex = key.indexOf('.', start)) != -1) {
                String part = key.substring(start, dotIndex);
                Object node = current.get(part);
                if (!(node instanceof Map)) {
                    Map<String, Object> next = new LinkedHashMap<>();
                    current.put(part, next);
                    current = next;
                } else {
                    //noinspection unchecked
                    current = (Map<String, Object>) node;
                }
                start = dotIndex + 1;
            }

            current.put(key.substring(start), resolvedValue);
        }
        return Collections.unmodifiableMap(root);
    }

    /**
     * 根据前缀获取结构化配置子树
     *
     * @param prefix 前缀
     * @return 子树对象（可能是 Map 或 String），如果不存在则返回 null
     */
    public Object getUnflattenedValue(String prefix) {
        if (StrUtil.isEmpty(prefix)) {
            return unflattenedMap();
        }

        Map<String, Object> current = unflattenedMap();
        int start = 0;
        int dotIndex;
        while ((dotIndex = prefix.indexOf('.', start)) != -1) {
            String part = prefix.substring(start, dotIndex);
            Object node = current.get(part);
            if (!(node instanceof Map)) {
                return null;
            }
            //noinspection unchecked
            current = (Map<String, Object>) node;
            start = dotIndex + 1;
        }

        return current.get(prefix.substring(start));
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
