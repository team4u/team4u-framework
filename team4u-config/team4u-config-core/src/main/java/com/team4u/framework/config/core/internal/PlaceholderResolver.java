package com.team4u.framework.config.core.internal;

import com.team4u.framework.base.util.StringUtil;
import com.team4u.framework.config.core.domain.ConfigSnapshot;

import java.util.HashSet;
import java.util.Set;

/**
 * 配置占位符解析器
 * <p>
 * 用于处理诸如 {@code ${key}} 或 {@code ${key:defaultValue}} 形式的配置项引用。
 * 本实现具备以下特性：
 * <ul>
 *     <li>支持递归解析，例如：{@code ${db.${env}.host}}</li>
 *     <li>支持默认值指定，例如：{@code ${server.port:8080}}</li>
 *     <li>内置循环依赖检测，防止无限递归</li>
 *     <li>严格的递归深度限制（默认最大 20 层）</li>
 * </ul>
 * </p>
 */
public class PlaceholderResolver {

    private static final String PREFIX = "${";
    private static final String SUFFIX = "}";
    private static final String SEPARATOR = ":";
    /**
     * 最大递归深度，防止复杂配置导致的栈溢出
     */
    private static final int MAX_DEPTH = 20;

    /**
     * 执行占位符替换
     *
     * @param value    待解析的原始字符串
     * @param snapshot 提供键值对检索能力的配置快照
     * @return 替换占位符后的最终字符串
     */
    public static String resolve(String value, ConfigSnapshot snapshot) {
        return resolve(value, snapshot, new HashSet<>(), 0);
    }

    /**
     * 执行占位符替换（支持外部传入已访问键集合）
     */
    public static String resolve(String value, ConfigSnapshot snapshot, Set<String> visitedKeys) {
        return resolve(value, snapshot, visitedKeys, 0);
    }

    /**
     * 内部递归解析核心逻辑
     *
     * @param value        待解析值
     * @param snapshot     配置快照
     * @param visitedKeys  记录解析路径上的键，用于循环依赖判定
     * @param currentDepth 当前递归层级
     */
    private static String resolve(String value, ConfigSnapshot snapshot, Set<String> visitedKeys, int currentDepth) {
        if (currentDepth > MAX_DEPTH) {
            throw new IllegalArgumentException(
                    "Maximum recursion depth reached for placeholder resolution: " + MAX_DEPTH);
        }

        if (StringUtil.isBlank(value) || !value.contains(PREFIX)) {
            return value;
        }

        StringBuilder result = new StringBuilder(value);
        int cursor = 0;

        while (cursor < result.length()) {
            int startIndex = result.indexOf(PREFIX, cursor);
            if (startIndex == -1) {
                break;
            }

            // 寻找匹配的结束标记，考虑嵌套情况
            int endIndex = findMatchSuffixIndex(result, startIndex);
            if (endIndex == -1) {
                cursor = startIndex + PREFIX.length();
                continue;
            }

            // 提取占位符内容部分
            String placeholder = result.substring(startIndex + PREFIX.length(), endIndex);
            // 首先递归解析占位符内容本身（支持动态键名）
            String resolvedPlaceholder = resolve(placeholder, snapshot, visitedKeys, currentDepth + 1);

            // 根据解析后的键名获取最终配置值
            String resolvedValue = resolveSinglePlaceholder(resolvedPlaceholder, snapshot, visitedKeys,
                    currentDepth + 1);

            if (resolvedValue != null) {
                result.replace(startIndex, endIndex + SUFFIX.length(), resolvedValue);
                cursor = startIndex + resolvedValue.length();
            } else {
                // 若无法解析且无默认值，保持原始占位符文本，继续处理后续内容
                cursor = endIndex + SUFFIX.length();
            }
        }

        return result.toString();
    }

    /**
     * 解析单个具体的占位符定义
     */
    private static String resolveSinglePlaceholder(String placeholder, ConfigSnapshot snapshot, Set<String> visitedKeys,
                                                   int currentDepth) {
        PlaceholderProperty property = parseProperty(placeholder);

        // 循环依赖检查：同一个路径上不允许重复出现同一个配置键
        if (!visitedKeys.add(property.key)) {
            throw new IllegalArgumentException("Circular dependency detected for placeholder key: " + property.key);
        }

        try {
            // 检索配置值，若缺失则尝试回退到默认值
            String value = snapshot.get(property.key).orElse(property.defaultValue);

            if (value != null && value.contains(PREFIX)) {
                // 解析出的值若仍包含占位符标记，继续向下执行递归解析
                return resolve(value, snapshot, visitedKeys, currentDepth + 1);
            }

            return value;
        } finally {
            // 解析完毕后出栈，不影响同级或父级路径的再次访问
            visitedKeys.remove(property.key);
        }
    }

    /**
     * 将占位符文本拆解为键名与默认值部分
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
     * 检索匹配的闭合后缀索引，考虑内部嵌套占位符的计数逻辑
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
     * 检查 StringBuilder 序列在指定偏移位置是否以目标前缀开始
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
     * 占位符结构封装类
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
