package com.team4u.framework.config.core.domain;

import com.team4u.framework.base.util.CollectionUtil;
import com.team4u.framework.base.util.MapUtil;
import com.team4u.framework.base.util.StringUtil;
import com.team4u.framework.config.core.internal.PlaceholderResolver;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 配置快照实体
 * <p>
 * 代表配置中心在某一特定时刻的完整状态。
 * 该对象是不可变的，所有数据在构造时完成初始化。
 * 基于 {@link Collections#unmodifiableMap} 确保了并发环境下的读取安全性与一致性。
 * </p>
 */
public class ConfigSnapshot {
    private static final Logger log = LoggerFactory.getLogger(ConfigSnapshot.class);
    /**
     * 快照版本号，通常由时间戳或递增序列生成
     */
    @Getter
    private final long version;
    /**
     * 底层存储原始配置条目的映射表
     */
    @Getter
    private final Map<String, ConfigEntry> entries;
    /**
     * 归一化索引，用于支持松散匹配，存储“归一化键”到“原始键”的映射
     */
    private final Map<String, String> looseIndex;
    /**
     * 树形结构化视图缓存，用于支持嵌套对象的绑定
     */
    private final Map<String, Object> unflattenedMap;

    public ConfigSnapshot(long version, Map<String, ConfigEntry> entries) {
        this.version = version;
        this.entries = MapUtil.isEmpty(entries) ? Collections.emptyMap() : Collections.unmodifiableMap(entries);
        this.looseIndex = buildLooseIndex(this.entries.keySet());
        // 构造阶段预先构建结构化视图，并处理占位符逻辑
        this.unflattenedMap = buildUnflattenedMap();
    }

    /**
     * 配置键归一化处理
     * <p>
     * 处理逻辑：将键转为小写，并移除所有分隔符（包括点号、中划线和下划线）。
     * </p>
     *
     * @param key 原始键名
     * @return 归一化后的键名
     */
    public static String normalize(String key) {
        if (key == null) {
            return null;
        }
        return key.toLowerCase().replace(".", "").replace("-", "").replace("_", "");
    }

    private static int looseKeyPriority(String key) {
        if (key == null) {
            return Integer.MAX_VALUE;
        }
        if (key.matches("[a-z0-9]+(\\.[a-z0-9]+)+")) {
            return 0;
        }
        if (key.equals(key.toLowerCase()) && key.contains("-")) {
            return 1;
        }
        if (key.equals(key.toLowerCase()) && key.contains("_")) {
            return 2;
        }
        if (key.equals(key.toLowerCase())) {
            return 3;
        }
        return 4;
    }

    /**
     * 构建松散匹配所需的归一化索引
     */
    private Map<String, String> buildLooseIndex(Set<String> originalKeys) {
        if (CollectionUtil.isEmpty(originalKeys)) {
            return Collections.emptyMap();
        }
        Map<String, List<String>> collisions = new HashMap<>(originalKeys.size());
        for (String key : originalKeys) {
            String normalized = normalize(key);
            if (normalized != null) {
                collisions.computeIfAbsent(normalized, ignored -> new ArrayList<>()).add(key);
            }
        }

        Map<String, String> index = new HashMap<>(collisions.size());
        for (Map.Entry<String, List<String>> entry : collisions.entrySet()) {
            List<String> candidates = new ArrayList<>(entry.getValue());
            candidates.sort(Comparator.comparingInt(ConfigSnapshot::looseKeyPriority).thenComparing(String::compareTo));

            String winner = candidates.get(0);
            index.put(entry.getKey(), winner);

            if (candidates.size() > 1) {
                log.warn("Config loose-key collision detected: normalizedKey={}, winner={}, candidates={}",
                        entry.getKey(), winner, candidates);
            }
        }
        return Collections.unmodifiableMap(index);
    }

    /**
     * 智能松散获取配置值
     * <p>
     * 检索逻辑如下：
     * <ul>
     *     <li>优先尝试使用精确键名进行检索</li>
     *     <li>若未命中，则通过归一化索引查找匹配的真实键名并获取其值</li>
     * </ul>
     * </p>
     *
     * @param looseKey 原始或模糊的键名（如 "serverPort"）
     * @return 配置值的 Optional 包装
     */
    public Optional<String> getSmart(String looseKey) {
        // 尝试精确匹配
        Optional<String> directValue = get(looseKey);
        if (directValue.isPresent()) {
            return directValue;
        }

        // 尝试归一化匹配
        String normalized = normalize(looseKey);
        String realKey = looseIndex.get(normalized);

        return realKey != null ? get(realKey) : Optional.empty();
    }

    /**
     * 获取完整的嵌套结构化配置映射表
     *
     * @return 树形结构的配置 Map
     */
    public Map<String, Object> unflattenedMap() {
        return unflattenedMap;
    }

    /**
     * 构建嵌套的树形视图
     * <p>
     * 该过程会执行以下操作：
     * <ul>
     *     <li>解析并替换配置值中的占位符</li>
     *     <li>将扁平的点号分隔键（如 "a.b.c"）转换为嵌套的 Map 结构</li>
     * </ul>
     * </p>
     */
    private Map<String, Object> buildUnflattenedMap() {
        if (MapUtil.isEmpty(entries)) {
            return Collections.emptyMap();
        }

        Map<String, Object> root = new LinkedHashMap<>();
        Set<String> visitedKeys = new HashSet<>();

        for (Map.Entry<String, ConfigEntry> entry : entries.entrySet()) {
            String key = entry.getKey();
            ConfigEntry configEntry = entry.getValue();

            if (key == null || configEntry.isEmptyOrDeleted()) {
                continue;
            }

            // 执行占位符替换，提前解析可以减少运行时的递归负担
            String resolvedValue;
            try {
                resolvedValue = PlaceholderResolver.resolve(configEntry.getValue(), this, visitedKeys);
            } catch (IllegalArgumentException e) {
                // 若检测到循环依赖或递归深度超限，构造阶段保持原始值，确保快照创建成功
                resolvedValue = configEntry.getValue();
            }

            // 填充树形 Map 节点
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
     * 根据前缀检索特定的结构化子树
     *
     * @param prefix 配置前缀
     * @return 对应的子树对象（Map 或 String），若前缀不存在则返回 null
     */
    public Object getUnflattenedValue(String prefix) {
        if (StringUtil.isEmpty(prefix)) {
            return unflattenedMap();
        }

        // 处理前缀末尾的点号，统一查找路径
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
     * 检索指定键对应的完整配置条目
     *
     * @param key 配置键
     * @return 包含元数据的配置条目实体，若已失效则返回 empty
     */
    public Optional<ConfigEntry> getEntry(String key) {
        if (key == null) {
            return Optional.empty();
        }
        ConfigEntry entry = entries.get(key);
        if (entry != null && !entry.isEmptyOrDeleted()) {
            return Optional.of(entry);
        }
        return Optional.empty();
    }

    /**
     * 获取指定键的字符串配置值
     *
     * @param key 配置键
     * @return 配置值的 Optional 包装
     */
    public Optional<String> get(String key) {
        return getEntry(key).map(ConfigEntry::getValue);
    }

    /**
     * 根据前缀搜索配置项，并返回移除前缀后的子映射表
     * <p>
     * 示例：前缀为 "app.db."，配置 "app.db.url" 将映射为 "url" -> "jdbc..."。
     * </p>
     *
     * @param prefix 配置键前缀
     * @return 剥离前缀后的键值映射表
     */
    public Map<String, String> getByPrefix(String prefix) {
        if (MapUtil.isEmpty(entries) || StringUtil.isBlank(prefix)) {
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
