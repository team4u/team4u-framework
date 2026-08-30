package com.team4u.framework.base.util;

import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * HashUtil 单元测试
 *
 * @author jay.wu
 */
public class HashUtilTest {

    @Test
    public void testMurmur32() {
        byte[] data = "test data".getBytes(StandardCharsets.UTF_8);
        int hash1 = HashUtil.murmur32(data);
        int hash2 = HashUtil.murmur32(data);
        Assert.assertEquals(hash1, hash2);

        // 测试不同长度的尾部处理
        Assert.assertNotEquals(0, HashUtil.murmur32("a".getBytes()));
        Assert.assertNotEquals(0, HashUtil.murmur32("ab".getBytes()));
        Assert.assertNotEquals(0, HashUtil.murmur32("abc".getBytes()));
        Assert.assertNotEquals(0, HashUtil.murmur32("abcd".getBytes()));
    }

    @Test
    public void testMurmur32WithSeed() {
        byte[] data = "test data".getBytes(StandardCharsets.UTF_8);
        int hash1 = HashUtil.murmur32(data, 0);
        int hash2 = HashUtil.murmur32(data);
        // 验证默认种子 0 与无参数方法保持一致
        Assert.assertEquals(hash1, hash2);

        int hash3 = HashUtil.murmur32(data, 12345);
        // 验证不同的种子带来不同的哈希效果
        Assert.assertNotEquals(hash1, hash3);
    }

    @Test
    public void testMurmur64() {
        byte[] data = "test data".getBytes(StandardCharsets.UTF_8);
        long hash1 = HashUtil.murmur64(data);
        long hash2 = HashUtil.murmur64(data);
        Assert.assertEquals(hash1, hash2);

        // 验证 64 位实现不再是 32 位结果的简单拼接
        int h32 = HashUtil.murmur32(data);
        long combined = (long) h32 << 32 | (h32 & 0xFFFFFFFFL);
        Assert.assertNotEquals(combined, hash1);
    }
}
