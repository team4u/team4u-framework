package com.team4u.config.core.domain;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.team4u.config.core.internal.PlaceholderResolver;
import lombok.Getter;

import java.util.*;

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
     * 归一化索引：存储 "normalized_key" -> "real.original.Key"
     * 例如："serverPort" -> "server.port"
     */
    private final Map<String, String> looseIndex;
    /**
     * 结构化视图缓存
     */
    private final Map<String, Object> unflattenedMap;

    public ConfigSnapshot(long version, Map<String, ConfigEntry> entries) {
        this.version = version;
        this.entries = MapUtil.isEmpty(entries) ? Collections.emptyMap() : Collections.unmodifiableMap(entries);
        this.looseIndex = buildLooseIndex(this.entries.keySet());
        // 构造阶段积极构建结构化视图（预热占位符逻辑）
        this.unflattenedMap = buildUnflattenedMap();
    }

    /**
     * 统一的归一化算法：转小写，移除所有分隔符（点、中划线、下划线）
     *
     * @param key 原始键
     * @return 归一化后的键
     */
    public static String normalize(String key) {
        if (key == null) {
            return null;
        }
        return key.toLowerCase().replace(".", "").replace("-", "").replace("_", "");
    }

    /**
     * 构建归一化索引
     */
    private Map<String, String> buildLooseIndex(Set<String> originalKeys) {
        if (CollUtil.isEmpty(originalKeys)) {
            return Collections.emptyMap();
        }
        Map<String, String> index = new HashMap<>(originalKeys.size());
        for (String key : originalKeys) {
            String normalized = normalize(key);
            if (normalized != null) {
                // 如果有冲突（如 my-key 和 my_key），保留原本存在的即可
                index.putIfAbsent(normalized, key);
            }
        }
        return Collections.unmodifiableMap(index);
    }

    /**
     * 智能松散获取
     *
     * @param looseKey 模糊的 Key (例如 "serverPort")
     * @return 配置值
     */
    public Optional<String> getSmart(String looseKey) {
        // 1. 尝试直接获取（最快路径）
        Optional<String> directValue = get(looseKey);
        if (directValue.isPresent()) {
            return directValue;
        }

        // 2. 归一化后查索引
        String normalized = normalize(looseKey);
        String realKey = looseIndex.get(normalized);

        // 3. 用查到的真实 Key 去取值
        return realKey != null ? get(realKey) : Optional.empty();
    }

    /**
     * 获取全量结构化配置 Map（延迟加载并缓存）
     *
     * @return 嵌套的 Map 结构
     */
    public Map<String, Object> unflattenedMap() {
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

            // 解析占位符，积极解析可以消除运行时绑定的递归开销
            String resolvedValue;
            try {
                resolvedValue = PlaceholderResolver.resolve(configEntry.getValue(), this, visitedKeys);
            } catch (IllegalArgumentException e) {
                // 如果存在循环依赖或递归过深，在预加载阶段仅保持原始值，不抛出异常
                // 这样可以保证 ConfigSnapshot 构造成功，异常将在业务实际调用时触发
                resolvedValue = configEntry.getValue();
            }

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
                    // noinspection unchecked
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

        // 统一预处理前缀，去除结尾的 "." 方便统一查找逻辑
        String searchPrefix = prefix.endsWith(".") ? prefix.substring(0, prefix.length() - 1) : prefix;

        Map<String, Object> current = unflattenedMap();
        int start = 0;
        int dotIndex;
        while ((dotIndex = searchPrefix.indexOf('.', start)) != -1) {
            String part = searchPrefix.substring(start, dotIndex);
            Object node = current.get(part);
            if (!(node instanceof Map)) {
                return null;
            }
            // noinspection unchecked
            current = (Map<String, Object>) node;
            start = dotIndex + 1;
        }

        return current.get(searchPrefix.substring(start));
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
     * 支持按前缀搜索检索嵌套配置，返回剥离前缀后的键值映射对。
     * <p>
     * 例如前缀为 "app.db."，针对配置 "app.db.url" 将返回 "url" -> "jdbc..."。
     * <p>
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

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ConfigSnapshot{");
        sb.append("version=").append(version);
        sb.append(", entriesCount=").append(entries.size());
        sb.append(", entriesSummary=[");

        int count = 0;
        for (Map.Entry<String, ConfigEntry> entry : entries.entrySet()) {
            if (count > 0) {
                sb.append(", ");
            }
            if (count >= 10) {
                sb.append("...");
                break;
            }
            sb.append(entry.getKey()).append("=").append(entry.getValue().getValue());
            count++;
        }
        sb.append("]");
        sb.append(", looseIndexSize=").append(looseIndex.size());
        sb.append(", unflattenedMapRoots=").append(unflattenedMap.keySet());
        sb.append('}');
        return sb.toString();
    }
}
