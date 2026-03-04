package com.team4u.framework.mask.policy;

import com.team4u.framework.mask.MaskPolicy;
import com.team4u.framework.mask.MaskType;
import com.team4u.framework.mask.MaskUtils;

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
