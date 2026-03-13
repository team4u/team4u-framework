package com.team4u.framework.base.util;

import org.junit.Assert;
import org.junit.Test;

/**
 * DigestUtil 单元测试
 *
 * @author jay.wu
 */
public class DigestUtilTest {

    @Test
    public void testMd5Hex() {
        // MD5("123456") = e10adc3949ba59abbe56e057f20f883e
        Assert.assertEquals("e10adc3949ba59abbe56e057f20f883e", DigestUtil.md5Hex("123456"));
        Assert.assertNull(DigestUtil.md5Hex(null));
    }

    @Test
    public void testSha256Hex() {
        // SHA-256("123456") = 8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92
        Assert.assertEquals("8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92", DigestUtil.sha256Hex("123456"));
        Assert.assertNull(DigestUtil.sha256Hex(null));
    }
}
