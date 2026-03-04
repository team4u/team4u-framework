package com.team4u.mask.policy;

import com.team4u.mask.MaskPolicy;
import com.team4u.mask.MaskType;
import com.team4u.mask.MaskUtils;

/**
 * 地址脱敏策略 (保留前9个字符)
 *
 * @author jay.wu
 */
public class AddressMaskPolicy implements MaskPolicy {

    @Override
    public String key() {
        return MaskType.ADDRESS.name();
    }

    @Override
    public String mask(String value) {
        return MaskUtils.mask(value, 9, 0);
    }
}
