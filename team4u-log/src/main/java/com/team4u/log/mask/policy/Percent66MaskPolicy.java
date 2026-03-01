package com.team4u.log.mask.policy;

import com.team4u.log.mask.MaskPolicy;
import com.team4u.log.mask.MaskType;
import com.team4u.log.mask.MaskUtils;

/**
 * 对值的66%部分掩码
 *
 * @author jay.wu
 */
public class Percent66MaskPolicy implements MaskPolicy {

    @Override
    public String key() {
        return MaskType.PERCENT66.name();
    }

    @Override
    public String mask(String value) {
        return MaskUtils.maskByPercent(value, 66);
    }
}
