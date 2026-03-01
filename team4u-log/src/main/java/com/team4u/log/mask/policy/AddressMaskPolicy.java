package com.team4u.log.mask.policy;

import com.team4u.log.mask.MaskPolicy;
import com.team4u.log.mask.MaskType;
import com.team4u.log.mask.MaskUtils;

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
