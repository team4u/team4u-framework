package com.team4u.framework.base.util;

/**
 * 十六进制工具类
 * <p>
 * 提供字节数组与十六进制字符串之间的互相转换功能。
 *
 * @author jay.wu
 */
public class HexUtil {

    /**
     * 用于十六进制转换的字符数组
     */
    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    /**
     * 将字节数组转换为十六进制字符串
     *
     * @param data 待转换的字节数组
     * @return 十六进制字符串，长度为输入数组长度的两倍
     */
    public static String encodeHexStr(byte[] data) {
        char[] out = new char[data.length << 1];
        for (int i = 0, j = 0; i < data.length; i++) {
            out[j++] = DIGITS[(0xF0 & data[i]) >>> 4];
            out[j++] = DIGITS[0x0F & data[i]];
        }
        return new String(out);
    }

    /**
     * 将十六进制字符串转换为字节数组
     *
     * @param hexStr 十六进制字符串
     * @return 字节数组，长度为输入字符串长度的一半
     * @throws IllegalArgumentException 如果字符串长度不是偶数，或者包含非法的十六进制字符
     */
    public static byte[] decodeHex(String hexStr) {
        int len = hexStr.length();
        if ((len & 0x01) != 0) {
            throw new IllegalArgumentException("十六进制字符串长度必须为偶数");
        }
        byte[] out = new byte[len >> 1];
        for (int i = 0, j = 0; j < len; i++) {
            int f = Character.digit(hexStr.charAt(j++), 16) << 4;
            f = f | Character.digit(hexStr.charAt(j++), 16);
            out[i] = (byte) (f & 0xFF);
        }
        return out;
    }
}
