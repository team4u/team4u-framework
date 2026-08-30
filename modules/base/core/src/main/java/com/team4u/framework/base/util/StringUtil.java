package com.team4u.framework.base.util;

/**
 * 字符串工具类
 * <p>
 * 提供字符串判断、格式化、截取、比较等常用操作。
 * </p>
 *
 * @author jay.wu
 */
public class StringUtil {

    /**
     * 判断字符串是否为空
     *
     * @param str 待校验字符串
     * @return 如果为 {@code null} 或长度为 0，则返回 {@code true}
     */
    public static boolean isEmpty(CharSequence str) {
        return str == null || str.length() == 0;
    }

    /**
     * 判断字符串是否不为空
     *
     * @param str 待校验字符串
     * @return 如果不为 {@code null} 且长度大于 0，则返回 {@code true}
     */
    public static boolean isNotEmpty(CharSequence str) {
        return !isEmpty(str);
    }

    /**
     * 判断字符串是否为空白（全为空格、空或 null）
     *
     * @param str 待校验字符串
     * @return 如果为空白，则返回 {@code true}
     */
    public static boolean isBlank(CharSequence str) {
        int length;
        if (str == null || (length = str.length()) == 0) {
            return true;
        }

        for (int i = 0; i < length; i++) {
            if (!Character.isWhitespace(str.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断字符串是否不为空白
     *
     * @param str 待校验字符串
     * @return 如果不为空白，则返回 {@code true}
     */
    public static boolean isNotBlank(CharSequence str) {
        return !isBlank(str);
    }

    /**
     * 格式化字符串，使用 {} 作为占位符
     * <p>
     * 例如：{@code simpleFormat("Hello {}", "World")} 将返回 "Hello World"。
     * </p>
     *
     * @param template 模板字符串
     * @param args     参数
     * @return 格式化后的字符串
     */
    public static String simpleFormat(String template, Object... args) {
        if (isEmpty(template) || args == null || args.length == 0) {
            return template;
        }

        StringBuilder sb = new StringBuilder(template.length() + 50);
        int lastIndex = 0;
        for (Object arg : args) {
            int index = template.indexOf("{}", lastIndex);
            if (index == -1) {
                break;
            }
            sb.append(template, lastIndex, index);
            sb.append(arg);
            lastIndex = index + 2;
        }
        sb.append(template.substring(lastIndex));
        return sb.toString();
    }

    /**
     * 将字符串首字母转换为小写
     *
     * @param str 原始字符串
     * @return 首字母转小写后的字符串，若原始串为空或首字母本就是小写，则返回原串
     */
    public static String lowerFirst(String str) {
        if (isEmpty(str) || Character.isLowerCase(str.charAt(0))) {
            return str;
        }
        return Character.toLowerCase(str.charAt(0)) + str.substring(1);
    }

    /**
     * 截取分隔符之前的字符串（不包含分隔符）
     *
     * @param str       原始字符串
     * @param separator 分隔符
     * @return 截取后的子串，若未匹配到分隔符则返回原串
     */
    public static String subBefore(String str, String separator) {
        return subBefore(str, separator, false);
    }

    /**
     * 截取分隔符之前的字符串
     *
     * @param str       原始字符串
     * @param separator 分隔符
     * @param isInclude 是否包含分隔符本身
     * @return 截取后的子串
     */
    public static String subBefore(String str, String separator, boolean isInclude) {
        if (isEmpty(str) || separator == null) {
            return str;
        }
        int index = str.indexOf(separator);
        if (index == -1) {
            return str;
        }
        return str.substring(0, isInclude ? index + separator.length() : index);
    }

    /**
     * 截取分隔符之后的字符串（不包含分隔符）
     *
     * @param str       原始字符串
     * @param separator 分隔符
     * @return 截取后的子串，若未匹配到分隔符则返回空字符串
     */
    public static String subAfter(String str, String separator) {
        return subAfter(str, separator, false);
    }

    /**
     * 截取分隔符之后的字符串
     *
     * @param str       原始字符串
     * @param separator 分隔符
     * @param isInclude 是否包含分隔符本身
     * @return 截取后的子串
     */
    public static String subAfter(String str, String separator, boolean isInclude) {
        if (isEmpty(str)) {
            return str;
        }
        if (separator == null) {
            return "";
        }
        int index = str.indexOf(separator);
        if (index == -1) {
            return "";
        }
        return str.substring(isInclude ? index : index + separator.length());
    }

    /**
     * 比较两个字符串是否内容相等
     *
     * @param str1 字符串1
     * @param str2 字符串2
     * @return 若相等返回 {@code true}，否则返回 {@code false}
     */
    public static boolean equals(CharSequence str1, CharSequence str2) {
        if (str1 == str2) {
            return true;
        }
        if (str1 == null || str2 == null) {
            return false;
        }
        return str1.toString().equals(str2.toString());
    }

    /**
     * 检查字符串是否包含指定子串
     *
     * @param str       主字符串
     * @param searchStr 被查找的子串
     * @return 若包含则返回 {@code true}
     */
    public static boolean contains(CharSequence str, CharSequence searchStr) {
        if (str == null || searchStr == null) {
            return false;
        }
        return str.toString().contains(searchStr);
    }

    /**
     * 判断字符串是否被指定的前缀和后缀包含
     *
     * @param str    原始字符串
     * @param prefix 前缀
     * @param suffix 后缀
     * @return 如果同时匹配前缀和后缀，返回 {@code true}
     */
    public static boolean isWrap(CharSequence str, CharSequence prefix, CharSequence suffix) {
        if (str == null || prefix == null || suffix == null) {
            return false;
        }
        String s = str.toString();
        return s.startsWith(prefix.toString()) && s.endsWith(suffix.toString());
    }

    /**
     * 比较两个版本号，格式如 1.0.2.1
     *
     * @param v1 版本 1
     * @param v2 版本 2
     * @return {@code 0} 表示相等，{@code 1} 表示第一个版本大，{@code -1} 表示第二个版本大
     */
    public static int compareVersion(String v1, String v2) {
        if (v1 == null && v2 == null)
            return 0;
        if (v1 == null)
            return -1;
        if (v2 == null)
            return 1;

        String[] v1Array = v1.split("\\.");
        String[] v2Array = v2.split("\\.");
        int length = Math.max(v1Array.length, v2Array.length);
        for (int i = 0; i < length; i++) {
            String p1 = i < v1Array.length ? v1Array[i] : "0";
            String p2 = i < v2Array.length ? v2Array[i] : "0";

            try {
                // 尝试数字比较（处理前导 0）
                int num1 = Integer.parseInt(p1);
                int num2 = Integer.parseInt(p2);
                if (num1 > num2)
                    return 1;
                if (num1 < num2)
                    return -1;
            } catch (NumberFormatException e) {
                // 无法转为数字，退化为字符串字典序比较
                int comp = p1.compareTo(p2);
                if (comp != 0)
                    return comp > 0 ? 1 : -1;
            }
        }
        return 0;
    }
}
