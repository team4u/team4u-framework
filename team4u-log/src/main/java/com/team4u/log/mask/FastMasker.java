package com.team4u.log.mask;

/**
 * 高性能掩码处理器
 * <p>
 * 直接操作字符串，避免正则匹配，提升处理效率。
 */
public class FastMasker {

    /**
     * 执行脱敏处理
     *
     * @param value 原始字符串
     * @param type  脱敏类型
     * @return 脱敏后的字符串
     */
    public static String mask(String value, MaskType type) {
        if (value == null || value.isEmpty()) {
            return value;
        }

        switch (type) {
            case PHONE:
                if (value.length() >= 11) {
                    return value.substring(0, 3) + "****" + value.substring(value.length() - 4);
                }
                return "***";
            case NAME:
                if (value.length() <= 1) {
                    return value;
                }
                if (value.length() == 2) {
                    return value.charAt(0) + "*";
                }
                return value.charAt(0) + "*" + value.substring(value.length() - 1);
            case IDCARD:
                if (value.length() >= 10) {
                    return value.substring(0, 3) + "***********" + value.substring(value.length() - 4);
                }
                return "******************";
            case PASSWORD:
                return "******";
            default:
                return "***";
        }
    }
}
