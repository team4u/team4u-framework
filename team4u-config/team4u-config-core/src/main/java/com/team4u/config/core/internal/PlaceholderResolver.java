package com.team4u.config.core.internal;

import cn.hutool.core.util.StrUtil;
import com.team4u.config.core.domain.ConfigSnapshot;

import java.util.HashSet;
import java.util.Set;

/**
 * 占位符解析器
 * <p>
 * 用于处理诸如 {@code ${key}} 或 {@code ${key:defaultValue}} 形式的占位符。
 */
public class PlaceholderResolver {

    private static final String PREFIX = "${";
    private static final String SUFFIX = "}";
    private static final String SEPARATOR = ":";
    private static final int MAX_DEPTH = 20;

    /**
     * 解析字符串中的占位符
     *
     * @param value    包含占位符的原始字符串
     * @param snapshot 当前配置快照，提供键值对查询能力
     * @return 解析并替换后的字符串
     */
    public static String resolve(String value, ConfigSnapshot snapshot) {
        return resolve(value, snapshot, new HashSet<>(), 0);
    }

    /**
     * 解析字符串中的占位符（支持复用已访问键集合）
     *
     * @param value       包含占位符的原始字符串
     * @param snapshot    当前配置快照
     * @param visitedKeys 已访问的键集合，用于循环依赖检测
     * @return 解析并替换后的字符串
     */
    public static String resolve(String value, ConfigSnapshot snapshot, Set<String> visitedKeys) {
        return resolve(value, snapshot, visitedKeys, 0);
    }

    /**
     * 解析字符串中的占位符（支持复用已访问键集合及递归深度控制）
     *
     * @param value        包含占位符的原始字符串
     * @param snapshot     当前配置快照
     * @param visitedKeys  已访问的键集合，用于循环依赖检测
     * @param currentDepth 当前递归深度
     * @return 解析并替换后的字符串
     */
    private static String resolve(String value, ConfigSnapshot snapshot, Set<String> visitedKeys, int currentDepth) {
        if (currentDepth > MAX_DEPTH) {
            throw new IllegalArgumentException(
                    "Maximum recursion depth reached for placeholder resolution: " + MAX_DEPTH);
        }

        if (StrUtil.isBlank(value) || !value.contains(PREFIX)) {
            return value;
        }

        StringBuilder result = new StringBuilder(value);
        int cursor = 0;

        while (cursor < result.length()) {
            int startIndex = result.indexOf(PREFIX, cursor);
            if (startIndex == -1) {
                break;
            }

            int endIndex = findMatchSuffixIndex(result, startIndex);
            if (endIndex == -1) {
                // 未找到匹配的后缀，跳过当前前缀继续处理
                cursor = startIndex + PREFIX.length();
                continue;
            }

            // 提取占位符内容，例如 "key:default"
            String placeholder = result.substring(startIndex + PREFIX.length(), endIndex);
            // 递归解析占位符内容中可能存在的嵌套占位符（例如：${db.${env}.host}）
            String resolvedPlaceholder = resolve(placeholder, snapshot, visitedKeys, currentDepth + 1);

            // 解析占位符并获取最终替换值
            String resolvedValue = resolveSinglePlaceholder(resolvedPlaceholder, snapshot, visitedKeys,
                    currentDepth + 1);

            if (resolvedValue != null) {
                // 替换占位符并从新位置继续扫描
                result.replace(startIndex, endIndex + SUFFIX.length(), resolvedValue);
                cursor = startIndex + resolvedValue.length();
            } else {
                // 无法解析且无默认值，保持原样并跳过
                cursor = endIndex + SUFFIX.length();
            }
        }

        return result.toString();
    }

    /**
     * 处理单个占位符内容的解析
     *
     * @param placeholder  占位符内容（不含 ${ 和 }）
     * @param snapshot     配置快照
     * @param visitedKeys  访问过的键集合
     * @param currentDepth 当前递归深度
     */
    private static String resolveSinglePlaceholder(String placeholder, ConfigSnapshot snapshot, Set<String> visitedKeys,
                                                   int currentDepth) {
        PlaceholderProperty property = parseProperty(placeholder);

        // 循环依赖检查
        if (!visitedKeys.add(property.key)) {
            throw new IllegalArgumentException("Circular dependency detected for placeholder key: " + property.key);
        }

        try {
            // 从快照中获取值，如果不存在则使用默认值
            String value = snapshot.get(property.key).orElse(property.defaultValue);

            if (value != null && value.contains(PREFIX)) {
                // 如果解析出的值仍包含占位符，递归解析（例如：${key} 指向的值为 ${anotherKey}）
                return resolve(value, snapshot, visitedKeys, currentDepth + 1);
            }

            return value;
        } finally {
            // 解析完成后移除，以便其他路径可以再次访问
            visitedKeys.remove(property.key);
        }
    }

    /**
     * 将占位符内容解析为键和默认值
     */
    private static PlaceholderProperty parseProperty(String placeholder) {
        int separatorIndex = placeholder.indexOf(SEPARATOR);
        if (separatorIndex == -1) {
            return new PlaceholderProperty(placeholder, null);
        }

        String key = placeholder.substring(0, separatorIndex);
        String defaultValue = placeholder.substring(separatorIndex + SEPARATOR.length());
        return new PlaceholderProperty(key, defaultValue);
    }

    /**
     * 寻找与当前前缀匹配的后缀索引（支持嵌套检测）
     */
    private static int findMatchSuffixIndex(StringBuilder sequence, int startIndex) {
        int nestedCount = 0;
        int len = sequence.length();
        int prefixLen = PREFIX.length();

        for (int i = startIndex + prefixLen; i < len; i++) {
            if (startsWith(sequence, i, PREFIX)) {
                nestedCount++;
                i += prefixLen - 1;
            } else if (startsWith(sequence, i, SUFFIX)) {
                if (nestedCount == 0) {
                    return i;
                }
                nestedCount--;
            }
        }
        return -1;
    }

    /**
     * 检查字符串序列在指定位置是否以特定前缀开始
     */
    private static boolean startsWith(StringBuilder sequence, int index, String prefix) {
        int prefixLen = prefix.length();
        if (index + prefixLen > sequence.length()) {
            return false;
        }

        for (int i = 0; i < prefixLen; i++) {
            if (sequence.charAt(index + i) != prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 占位符属性封装类
     */
    private static class PlaceholderProperty {
        private final String key;
        private final String defaultValue;

        public PlaceholderProperty(String key, String defaultValue) {
            this.key = key;
            this.defaultValue = defaultValue;
        }
    }
}
