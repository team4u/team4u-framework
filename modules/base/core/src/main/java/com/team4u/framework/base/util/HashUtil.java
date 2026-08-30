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
     * 采用 MurmurHash3 x64_128(seed=0) 的低 64 位输出。
     * </p>
     * @param data 待计算的字节数组
     * @return 64 位哈希值
     */
    public static long murmur64(byte[] data) {
        final long c1 = 0x87c37b91114253d5L;
        final long c2 = 0x4cf5ad432745937fL;
        long h1 = 0L;
        long h2 = 0L;
        int length = data.length;
        int nblocks = length >> 4;

        for (int i = 0; i < nblocks; i++) {
            int index = i << 4;
            long k1 = getLittleEndianLong(data, index);
            long k2 = getLittleEndianLong(data, index + 8);

            k1 *= c1;
            k1 = Long.rotateLeft(k1, 31);
            k1 *= c2;
            h1 ^= k1;

            h1 = Long.rotateLeft(h1, 27);
            h1 += h2;
            h1 = h1 * 5 + 0x52dce729;

            k2 *= c2;
            k2 = Long.rotateLeft(k2, 33);
            k2 *= c1;
            h2 ^= k2;

            h2 = Long.rotateLeft(h2, 31);
            h2 += h1;
            h2 = h2 * 5 + 0x38495ab5;
        }

        long k1 = 0L;
        long k2 = 0L;
        int tailStart = nblocks << 4;
        switch (length & 15) {
            case 15:
                k2 ^= ((long) data[tailStart + 14] & 0xffL) << 48;
            case 14:
                k2 ^= ((long) data[tailStart + 13] & 0xffL) << 40;
            case 13:
                k2 ^= ((long) data[tailStart + 12] & 0xffL) << 32;
            case 12:
                k2 ^= ((long) data[tailStart + 11] & 0xffL) << 24;
            case 11:
                k2 ^= ((long) data[tailStart + 10] & 0xffL) << 16;
            case 10:
                k2 ^= ((long) data[tailStart + 9] & 0xffL) << 8;
            case 9:
                k2 ^= ((long) data[tailStart + 8] & 0xffL);
                k2 *= c2;
                k2 = Long.rotateLeft(k2, 33);
                k2 *= c1;
                h2 ^= k2;
            case 8:
                k1 ^= ((long) data[tailStart + 7] & 0xffL) << 56;
            case 7:
                k1 ^= ((long) data[tailStart + 6] & 0xffL) << 48;
            case 6:
                k1 ^= ((long) data[tailStart + 5] & 0xffL) << 40;
            case 5:
                k1 ^= ((long) data[tailStart + 4] & 0xffL) << 32;
            case 4:
                k1 ^= ((long) data[tailStart + 3] & 0xffL) << 24;
            case 3:
                k1 ^= ((long) data[tailStart + 2] & 0xffL) << 16;
            case 2:
                k1 ^= ((long) data[tailStart + 1] & 0xffL) << 8;
            case 1:
                k1 ^= ((long) data[tailStart] & 0xffL);
                k1 *= c1;
                k1 = Long.rotateLeft(k1, 31);
                k1 *= c2;
                h1 ^= k1;
            default:
                break;
        }

        h1 ^= length;
        h2 ^= length;

        h1 += h2;
        h2 += h1;

        h1 = fmix64(h1);
        h2 = fmix64(h2);

        h1 += h2;
        return h1;
    }

    private static long getLittleEndianLong(byte[] data, int index) {
        return ((long) data[index] & 0xffL)
                | (((long) data[index + 1] & 0xffL) << 8)
                | (((long) data[index + 2] & 0xffL) << 16)
                | (((long) data[index + 3] & 0xffL) << 24)
                | (((long) data[index + 4] & 0xffL) << 32)
                | (((long) data[index + 5] & 0xffL) << 40)
                | (((long) data[index + 6] & 0xffL) << 48)
                | (((long) data[index + 7] & 0xffL) << 56);
    }

    private static long fmix64(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return value;
    }
}
