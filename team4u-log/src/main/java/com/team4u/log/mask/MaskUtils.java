package com.team4u.log.mask;

/**
 * 脱敏处理工具类
 *
 * @author jay.wu
 */
public class MaskUtils {

    /**
     * 对字符串进行掩码处理
     *
     * @param value  原始字符串
     * @param prefix 前缀保留长度
     * @param suffix 后缀保留长度
     * @return 掩码后的字符串
     */
    public static String mask(String value, int prefix, int suffix) {
        if (value == null || value.isEmpty()) {
            return value;
        }

        int length = value.length();
        if (prefix + suffix >= length) {
            return value;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(value, 0, prefix);
        for (int i = 0; i < length - prefix - suffix; i++) {
            sb.append("*");
        }
        sb.append(value, length - suffix, length);
        return sb.toString();
    }

    /**
     * 按百分比进行掩码处理
     *
     * @param value   原始字符串
     * @param percent 掩码部分占总长度的百分比 (0-100)
     * @return 掩码后的字符串
     */
    public static String maskByPercent(String value, int percent) {
        if (value == null || value.isEmpty()) {
            return value;
        }

        int length = value.length();
        int maskLength = (int) Math.ceil(length * (percent / 100.0));
        if (maskLength <= 0) {
            return value;
        }

        if (maskLength >= length) {
            return hide(value);
        }

        // 居中掩码
        int start = (length - maskLength) / 2;
        StringBuilder sb = new StringBuilder();
        sb.append(value, 0, start);
        for (int i = 0; i < maskLength; i++) {
            sb.append("*");
        }
        sb.append(value, start + maskLength, length);
        return sb.toString();
    }

    /**
     * 限制字符串最大长度，超出则截取并添加省略号
     *
     * @param value 原始字符串
     * @param limit 最大显示长度
     * @return 截取后的字符串
     */
    public static String limit(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit);
    }

    /**
     * 全部掩码，固定返回单个星号
     *
     * @param value 原始字符串
     * @return 掩码后的字符串 ("*")
     */
    public static String hide(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return "*";
    }
}
