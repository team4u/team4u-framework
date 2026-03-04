package com.team4u.mask.policy;

import com.team4u.mask.MaskPolicy;
import com.team4u.mask.MaskType;
import com.team4u.mask.MaskUtils;

/**
 * 银行卡号脱敏策略 (保留前4后2)
 *
 * @author jay.wu
 */
public class BankCardNoMaskPolicy implements MaskPolicy {

    @Override
    public String key() {
        return MaskType.BANK_CARD_NO.name();
    }

    @Override
    public String mask(String value) {
        return MaskUtils.mask(value, 4, 2);
    }
}
