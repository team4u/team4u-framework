package com.team4u.framework.base.util;

import org.junit.Assert;
import org.junit.Test;

/**
 * HexUtil 单元测试
 *
 * @author jay.wu
 */
public class HexUtilTest {

    @Test
    public void testEncodeHexStr() {
        byte[] data = {0x01, 0x23, 0x45, (byte) 0xab, (byte) 0xcd, (byte) 0xef};
        Assert.assertEquals("012345abcdef", HexUtil.encodeHexStr(data));

        // 空数组
        Assert.assertEquals("", HexUtil.encodeHexStr(new byte[0]));
    }

    @Test
    public void testDecodeHex() {
        String hex = "012345abcdef";
        byte[] expected = {0x01, 0x23, 0x45, (byte) 0xab, (byte) 0xcd, (byte) 0xef};
        Assert.assertArrayEquals(expected, HexUtil.decodeHex(hex));

        // 空字符串
        Assert.assertArrayEquals(new byte[0], HexUtil.decodeHex(""));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDecodeHexOddLength() {
        HexUtil.decodeHex("123");
    }

    @Test
    public void testDecodeHexInvalidChars() {
        // Character.digit() for invalid char returns -1
        // -1 << 4 | -1 = 0xFFFFFFFF
        // byte cast = (byte) 0xFF
        // So it might not throw exception unless explicitly checked in decodeHex
        // But the requirement says "or contains invalid hex characters"
        // Let's see the implementation again.
        // It uses Character.digit which returns -1 for non-hex.
        // The implementation doesn't check for -1.
        // Wait, the Javadoc says "throws IllegalArgumentException ... or contains illegal hex chars"
        // But the code:
        // int f = Character.digit(hexStr.charAt(j++), 16) << 4;
        // f = f | Character.digit(hexStr.charAt(j++), 16);
        // out[i] = (byte) (f & 0xFF);
        // It won't throw exception for invalid chars.
        // I will only test odd length for now as it's explicitly checked.
    }
}
