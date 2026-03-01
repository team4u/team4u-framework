package com.team4u.log.mask;

import org.junit.Assert;
import org.junit.Test;

/**
 * 高性能掩码处理器单元测试
 */
public class FastMaskerTest {

    @Test
    public void testMaskPhone() {
        Assert.assertEquals("138****5678", FastMasker.mask("13812345678", MaskType.PHONE));
        Assert.assertEquals("***", FastMasker.mask("123", MaskType.PHONE));
        Assert.assertNull(FastMasker.mask(null, MaskType.PHONE));
    }

    @Test
    public void testMaskName() {
        Assert.assertEquals("周*伦", FastMasker.mask("周杰伦", MaskType.NAME));
        Assert.assertEquals("周*", FastMasker.mask("周杰", MaskType.NAME));
        Assert.assertEquals("周", FastMasker.mask("周", MaskType.NAME));
    }

    @Test
    public void testMaskIdCard() {
        Assert.assertEquals("440***********1234", FastMasker.mask("440111199001011234", MaskType.IDCARD));
        Assert.assertEquals("******************", FastMasker.mask("12345", MaskType.IDCARD));
    }

    @Test
    public void testMaskPassword() {
        Assert.assertEquals("******", FastMasker.mask("mySecretPassword", MaskType.PASSWORD));
    }

    @Test
    public void testEmptyValue() {
        Assert.assertEquals("", FastMasker.mask("", MaskType.PHONE));
        Assert.assertNull(FastMasker.mask(null, MaskType.PHONE));
    }

    @Test
    public void testUnknownType() {
        // 默认脱敏为 ***
        Assert.assertEquals("***", FastMasker.mask("anyValue", MaskType.DYNAMIC));
    }
}
