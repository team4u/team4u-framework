package com.team4u.framework.base.util;

/**
 * 哈希工具类
 * <p>
 * 提供高性能的哈希算法实现，目前主要包含 MurmurHash3 算法。
 * MurmurHash 是一种非加密型哈希函数，适用于一般的哈希检索操作。
 *
 * @author jay.wu
 */
public class HashUtil {

    /**
     * 计算 32 位 MurmurHash3 值（默认 seed = 0）
     * <p>
     * 这是一种极简版的实现，适用于对字节数组进行快速哈希计算。
     *
     * @param data 待计算的字节数组
     * @return 32 位哈希值
     */
    public static int murmur32(byte[] data) {
        return murmur32(data, 0);
    }

    /**
     * 计算 32 位 MurmurHash3 值
     *
     * @param data 待计算的字节数组
     * @param seed 计算时使用的种子
     * @return 32 位哈希值
     */
    public static int murmur32(byte[] data, int seed) {
        int h1 = seed;
        int len = data.length;
        int nblocks = len / 4;

        for (int i = 0; i < nblocks; i++) {
            int index = i * 4;
            int k1 = (data[index] & 0xff)
                    | ((data[index + 1] & 0xff) << 8)
                    | ((data[index + 2] & 0xff) << 16)
                    | ((data[index + 3] & 0xff) << 24);

            k1 *= 0xcc9e2d51;
            k1 = Integer.rotateLeft(k1, 15);
            k1 *= 0x1b873593;

            h1 ^= k1;
            h1 = Integer.rotateLeft(h1, 13);
            h1 = h1 * 5 + 0xe6546b64;
        }

        int k1 = 0;
        int tail = nblocks * 4;
        switch (len & 3) {
            case 3:
                k1 ^= (data[tail + 2] & 0xff) << 16;
                // fall through
            case 2:
                k1 ^= (data[tail + 1] & 0xff) << 8;
                // fall through
            case 1:
                k1 ^= (data[tail] & 0xff);
                k1 *= 0xcc9e2d51;
                k1 = Integer.rotateLeft(k1, 15);
                k1 *= 0x1b873593;
                h1 ^= k1;
        }

        h1 ^= len;
        h1 ^= h1 >>> 16;
        h1 *= 0x85ebca6b;
        h1 ^= h1 >>> 13;
        h1 *= 0xc2b2ae35;
        h1 ^= h1 >>> 16;

        return h1;
    }

    /**
     * 计算 64 位 MurmurHash3 值
     * <p>
     * 这是一种极简版的实现，通过组合 32 位哈希结果来生成 64 位哈希值。
     *
     * @param data 待计算的字节数组
     * @return 64 位哈希值
     */
    public static long murmur64(byte[] data) {
        int hash32 = murmur32(data);
        return ((long) hash32 << 32) | (hash32 & 0xFFFFFFFFL);
    }
}