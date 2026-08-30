package com.team4u.framework.base.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 摘要加密工具类
 * <p>
 * 提供 MD5、SHA-256 等常用的哈希算法功能，支持将计算结果转换为十六进制字符串格式。
 *
 * @author jay.wu
 */
public class DigestUtil {

    /**
     * 计算 MD5 摘要并转换为十六进制字符串
     * <p>
     * 使用 UTF-8 字符集对输入字符串进行编码后再进行摘要计算。
     *
     * @param data 待计算摘要的字符串数据，若为 null 则返回 null
     * @return 长度为 32 的小写十六进制 MD5 字符串
     */
    public static String md5Hex(String data) {
        if (data == null) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 算法不存在", e);
        }
    }

    /**
     * 计算 SHA-256 摘要并转换为十六进制字符串
     * <p>
     * 使用 UTF-8 字符集对输入字符串进行编码后再进行摘要计算。
     *
     * @param data 待计算摘要的字符串数据，若为 null 则返回 null
     * @return 长度为 64 的小写十六进制 SHA-256 字符串
     */
    public static String sha256Hex(String data) {
        if (data == null) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 算法不存在", e);
        }
    }

    /**
     * 将字节数组转换为十六进制字符串
     *
     * @param bytes 待转换的字节数组
     * @return 十六进制字符串
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
