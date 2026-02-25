package com.team4u.config.core.internal;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.map.MapUtil;
import com.team4u.config.core.domain.ConfigSnapshot;
import com.team4u.config.core.spi.ConfigBinder;

import java.util.HashMap;
import java.util.Map;

/**
 * 默认配置绑定器
 * <p>
 * 基于 Hutool 的 {@link BeanUtil} 和 {@link Convert} 实现配置绑定。
 * 支持松散绑定（驼峰、下划线、中划线互转）以及嵌套对象绑定。
 */
public class DefaultConfigBinder implements ConfigBinder {

    @Override
    public <T> T bind(ConfigSnapshot snapshot, String prefix, Class<T> type) {
        if (snapshot == null || type == null) {
            return null;
        }

        // 1. 获取前缀下所有配置项
        Map<String, String> prefixEntries = snapshot.getByPrefix(prefix);

        // 如果直接对应一个基本类型的值（例如请求 bind(snapshot, "app.port", Integer.class) 且 app.port 没有子节点）
        if (prefixEntries.isEmpty()) {
            // 尝试直接获取并解析该单一 key
            String singleValue = snapshot.get(prefix).orElse(null);
            if (singleValue != null) {
                String resolvedValue = PlaceholderResolver.resolve(singleValue, snapshot);
                return Convert.convert(type, resolvedValue);
            }
            return null;
        }

        // 如果当前仅有一个 entry 且 key 就是 prefix 本身，也作为单一值对待
        if (prefixEntries.size() == 1 && prefixEntries.containsKey(prefix)) {
            String resolvedValue = PlaceholderResolver.resolve(prefixEntries.get(prefix), snapshot);
            return Convert.convert(type, resolvedValue);
        }

        // 2. 将扁平化的 Key-Value 字典反展开 (Unflatten) 为嵌套 Map
        Map<String, Object> unflattenedMap = unflatten(prefixEntries, prefix, snapshot);

        if (MapUtil.isEmpty(unflattenedMap)) {
            return null;
        }

        // 3. 如果目标类型本身就是 Map，尝试直接转换
        if (Map.class.isAssignableFrom(type)) {
            return (T) unflattenedMap;
        }

        // 4. 将 Map 注入到 Java Bean 中，使用 Hutool 的 CopyOptions 支持松散绑定
        CopyOptions copyOptions = CopyOptions.create()
                .ignoreCase()
                .ignoreError(); // 忽略个别无法转换的字段错误

        // 由于旧版 hutool 的 ignoreCase 可能对中划线等特殊符号支持不完美，提前将 Map 中的分隔符去除
        Map<String, Object> processedMap = new HashMap<>();
        for (Map.Entry<String, Object> entry : unflattenedMap.entrySet()) {
            String key = entry.getKey().replace("-", "").replace("_", "");
            processedMap.put(key, entry.getValue());
        }

        return BeanUtil.toBean(processedMap, type, copyOptions);
    }

    /**
     * 将扁平键值对反展开为树形 Map 结构
     * 例如："db.host" -> "127.0.0.1", "db.port" -> "3306"
     * 将转换为 { "db": { "host": "127.0.0.1", "port": "3306" } }
     */
    private Map<String, Object> unflatten(Map<String, String> flatMap, String prefix, ConfigSnapshot snapshot) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, String> entry : flatMap.entrySet()) {
            // 本身的 prefix 在 getByPrefix 已经被截断，例如 prefix='server', server.host 会变成 entry(key='host', value=...)
            // 边缘情况：如果查询前缀正好也作为单独的键存在于整体配置里，它在 prefixEntries 中可能不会出现（因为 getByPrefix 是强求 '.' 后缀前缀的）。
            // ConfigSnapshot.getByPrefix("app") 会拉取 "app.xxx" -> "xxx" 格式的数据。
            String relKey = entry.getKey();
            String rawValue = entry.getValue();

            if (relKey.isEmpty()) {
                continue; // 保护逻辑
            }

            // 处理占位符
            String resolvedValue = PlaceholderResolver.resolve(rawValue, snapshot);

            // 按照 '.' 切分层级
            String[] parts = relKey.split("\\.");
            Map<String, Object> currentLevel = result;

            for (int i = 0; i < parts.length - 1; i++) {
                String part = parts[i];
                Object node = currentLevel.get(part);
                if (node instanceof Map) {
                    currentLevel = (Map<String, Object>) node;
                } else {
                    Map<String, Object> nextLevel = new HashMap<>();
                    currentLevel.put(part, nextLevel);
                    currentLevel = nextLevel;
                }
            }

            // 叶子节点赋值
            currentLevel.put(parts[parts.length - 1], resolvedValue);
        }

        return result;
    }
}
