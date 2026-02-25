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

    /**
     * 解析字符串中的占位符
     *
     * @param value    包含占位符的原始字符串
     * @param snapshot 当前配置快照，提供键值对查询能力
     * @return 解析并替换后的字符串
     */
    public static String resolve(String value, ConfigSnapshot snapshot) {
        if (StrUtil.isBlank(value)) {
            return value;
        }

        return resolve(value, snapshot, new HashSet<>());
    }

    private static String resolve(String value, ConfigSnapshot snapshot, Set<String> visitedKeys) {
        if (StrUtil.isBlank(value)) {
            return value;
        }

        int startIndex = value.indexOf(PREFIX);
        if (startIndex == -1) {
            return value;
        }

        StringBuilder result = new StringBuilder(value);
        int cursor = startIndex;

        while (cursor < result.length()) {
            int prefixIndex = result.indexOf(PREFIX, cursor);
            if (prefixIndex == -1) {
                break;
            }

            // 寻找匹配的后缀 '}'
            int suffixIndex = findMatchSuffixIndex(result, prefixIndex);
            if (suffixIndex == -1) {
                // 如果找不到闭合的 }，则表示不是合法的占位符，直接跳过处理
                cursor = prefixIndex + PREFIX.length();
                continue;
            }

            // 提取出占位符内部的内容，例如 key 或 key:default
            String placeholder = result.substring(prefixIndex + PREFIX.length(), suffixIndex);
            String originalPlaceholder = placeholder;

            // 如果内容中还嵌套有其他的占位符，先递归解析内部的
            if (placeholder.contains(PREFIX)) {
                placeholder = resolve(placeholder, snapshot, visitedKeys);
            }

            String targetKey;
            String defaultValue = null;

            int separatorIndex = placeholder.indexOf(SEPARATOR);
            if (separatorIndex != -1) {
                targetKey = placeholder.substring(0, separatorIndex);
                defaultValue = placeholder.substring(separatorIndex + SEPARATOR.length());
            } else {
                targetKey = placeholder;
            }

            // 循环依赖检查
            if (!visitedKeys.add(targetKey)) {
                throw new IllegalArgumentException("Circular dependency detected for placeholder key: " + targetKey);
            }

            // 查询实际包含的数据
            String resolvedValue = snapshot.get(targetKey).orElse(null);

            if (resolvedValue == null && defaultValue != null) {
                resolvedValue = defaultValue;
            }

            if (resolvedValue != null) {
                // 如果解析出的值内部还包含占位符，继续递归解析
                if (resolvedValue.contains(PREFIX)) {
                    resolvedValue = resolve(resolvedValue, snapshot, visitedKeys);
                }
                result.replace(prefixIndex, suffixIndex + SUFFIX.length(), resolvedValue);
                cursor = prefixIndex + resolvedValue.length();
            } else {
                // 无法解析且没有默认值，保持原样（或者可以抛错，由实现决定，这里选择优雅降级为原样）
                cursor = suffixIndex + SUFFIX.length();
            }

            // 当前占位符解析完毕，退出其依赖追踪
            visitedKeys.remove(targetKey);
        }

        return result.toString();
    }

    private static int findMatchSuffixIndex(StringBuilder sequence, int prefixIndex) {
        int nestedCount = 0;
        for (int i = prefixIndex + PREFIX.length(); i < sequence.length(); i++) {
            if (sequence.substring(i).startsWith(PREFIX)) {
                nestedCount++;
                i += PREFIX.length() - 1; // 跳过额外前缀字符
            } else if (sequence.substring(i).startsWith(SUFFIX)) {
                if (nestedCount == 0) {
                    return i;
                }
                nestedCount--;
                i += SUFFIX.length() - 1; // 跳过额外后缀字符
            }
        }
        return -1;
    }
}
