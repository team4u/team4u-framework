package com.team4u.mask;

/**
 * 标准脱敏算法标识
 *
 * @author jay.wu
 */
public enum MaskType {
    /**
     * 姓名
     */
    NAME,
    /**
     * 手机号码 (保留前3后3)
     */
    MOBILE,
    /**
     * 银行卡号 (保留前4后2)
     */
    BANK_CARD_NO,
    /**
     * 身份证号 (保留前5后2)
     */
    ID_CARD_NO,
    /**
     * 仅显示前1后1
     */
    B1A1,
    /**
     * 仅显示前2后2
     */
    B2A2,
    /**
     * 对值的66%部分掩码
     */
    PERCENT66,
    /**
     * 对值的66%部分掩码，并且最多显示10个字符
     */
    PERCENT66_LIMIT10,
    /**
     * 对值的1%部分掩码，并且最多显示200个字符
     */
    PERCENT1_LIMIT200,
    /**
     * 地址 (前9个字符)
     */
    ADDRESS,
    /**
     * 电子邮箱
     */
    EMAIL,
    /**
     * 不进行掩码，返回明文
     */
    NONE,
    /**
     * 全部掩码，固定为*
     */
    HIDE,
    /**
     * 固定为null
     */
    NULL,
    /**
     * 密码
     */
    PASSWORD
}
