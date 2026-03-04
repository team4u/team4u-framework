package com.team4u.mask.policy;

import com.team4u.mask.MaskPolicy;
import com.team4u.mask.MaskType;
import com.team4u.mask.MaskUtils;

/**
 * 对值的66%部分掩码，并且最多显示10个字符
 *
 * @author jay.wu
 */
public class Percent66Limit10MaskPolicy implements MaskPolicy {

    @Override
    public String key() {
        return MaskType.PERCENT66_LIMIT10.name();
    }

    @Override
    public String mask(String value) {
        String masked = MaskUtils.maskByPercent(value, 66);
        return MaskUtils.limit(masked, 10);
    }
}
