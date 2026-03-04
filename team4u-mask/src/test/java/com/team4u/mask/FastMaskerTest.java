package com.team4u.mask;

import org.junit.Assert;
import org.junit.Test;

/**
 * 脱敏处理器单元测试
 *
 * @author jay.wu
 */
public class FastMaskerTest {

    @Test
    public void testMaskName() {
        // 中文姓名：3个及以下显示最后一个
        Assert.assertEquals("**伦", FastMasker.mask("周杰伦", MaskType.NAME));
        Assert.assertEquals("*杰", FastMasker.mask("周杰", MaskType.NAME));
        // 中文姓名：3个以上显示最后两个
        Assert.assertEquals("****艾山", FastMasker.mask("买买提·艾山", MaskType.NAME));
        // 英文姓名：显示前一后一
        Assert.assertEquals("j*y", FastMasker.mask("jay", MaskType.NAME));
        Assert.assertEquals("f***y", FastMasker.mask("fjayy", MaskType.NAME));
    }

    @Test
    public void testMaskMobile() {
        // 保留前3后3
        Assert.assertEquals("138*****678", FastMasker.mask("13812345678", MaskType.MOBILE));
    }

    @Test
    public void testMaskBankCard() {
        // 保留前4后2
        Assert.assertEquals("6222**********11", FastMasker.mask("6222123456789011", MaskType.BANK_CARD_NO));
    }

    @Test
    public void testMaskIdCard() {
        // 保留前5后2
        Assert.assertEquals("44011***********34", FastMasker.mask("440111199001011234", MaskType.ID_CARD_NO));
    }

    @Test
    public void testMaskB1A1() {
        Assert.assertEquals("1***5", FastMasker.mask("12345", MaskType.B1A1));
    }

    @Test
    public void testMaskB2A2() {
        Assert.assertEquals("12*45", FastMasker.mask("12345", MaskType.B2A2));
    }

    @Test
    public void testMaskPercent66() {
        // "1234567890", 长度10, 66%是7个字符，居中掩码
        // 10-7=3, 3/2=1, 所以从索引1开始掩码7个
        Assert.assertEquals("1*******90", FastMasker.mask("1234567890", MaskType.PERCENT66));
    }

    @Test
    public void testMaskPercent66Limit10() {
        Assert.assertEquals("1*******90", FastMasker.mask("1234567890", MaskType.PERCENT66_LIMIT10));
        // 123456789012345 (15), 66% is 10 chars, start = (15-10)/2 = 2.
        // Result: "12" + "**********" + "345" = "12**********345"
        // Limit 10: "12********"
        Assert.assertEquals("12********", FastMasker.mask("123456789012345", MaskType.PERCENT66_LIMIT10));
    }

    @Test
    public void testMaskPercent1Limit200() {
        // 长度10, 1%是1个字符，居中掩码
        Assert.assertEquals("1234*67890", FastMasker.mask("1234567890", MaskType.PERCENT1_LIMIT200));
    }

    @Test
    public void testMaskAddress() {
        // 保留前9个，"广东省广州市天河区体育西路" (13) -> 13-9 = 4个掩码
        Assert.assertEquals("广东省广州市天河区****", FastMasker.mask("广东省广州市天河区体育西路", MaskType.ADDRESS));
    }

    @Test
    public void testMaskEmail() {
        Assert.assertEquals("f****@gmail.com", FastMasker.mask("fjayy@gmail.com", MaskType.EMAIL));
    }

    @Test
    public void testMaskNone() {
        Assert.assertEquals("12345", FastMasker.mask("12345", MaskType.NONE));
    }

    @Test
    public void testMaskHide() {
        Assert.assertEquals("*", FastMasker.mask("12345", MaskType.HIDE));
    }

    @Test
    public void testMaskNull() {
        Assert.assertNull(FastMasker.mask("12345", MaskType.NULL));
    }

    @Test
    public void testUnknownType() {
        // 未知类型返回原值
        Assert.assertEquals("anyValue", FastMasker.mask("anyValue", "UNKNOWN"));
    }
}
