package com.team4u.framework.base.util;

/**
 * 数字工具类
 * <p>
 * 提供对数字字符串的解析和判别等常用操作。
 *
 * @author jay.wu
 */
public class NumberUtil {

    /**
     * 将字符串解析为 double，解析失败时返回默认值 0
     *
     * @param str 待解析字符串
     * @return 解析后的 double 值，若解析失败则返回 0
     */
    public static double parseDouble(String str) {
        if (str == null) {
            return 0;
        }
        try {
            return Double.parseDouble(str);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 将字符串解析为 int，解析失败时返回默认值 0
     *
     * @param str 待解析字符串
     * @return 解析后的 int 值，若解析失败则返回 0
     */
    public static int parseInt(String str) {
        if (str == null) {
            return 0;
        }
        try {
            return Integer.parseInt(str);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 判断字符串是否为数字
     *
     * @param str 待检查字符串
     * @return 如果字符串表示有效的数字（包括浮点数），则返回 true
     */
    public static boolean isNumber(CharSequence str) {
        if (StringUtil.isBlank(str)) {
            return false;
        }
        String s = str.toString();
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 判断字符串是否为整数
     *
     * @param str 待检查字符串
     * @return 如果字符串表示有效的整数，则返回 true
     */
    public static boolean isInteger(String str) {
        if (StringUtil.isBlank(str)) {
            return false;
        }
        try {
            Integer.parseInt(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
