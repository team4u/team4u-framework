package com.team4u.framework.mask.policy;

import com.team4u.framework.mask.MaskPolicy;
import com.team4u.framework.mask.MaskType;
import com.team4u.framework.mask.MaskUtils;

/**
 * 对值的1%部分掩码，并且最多显示200个字符
 *
 * @author jay.wu
 */
public class Percent1Limit200MaskPolicy implements MaskPolicy {

    @Override
    public String key() {
        return MaskType.PERCENT1_LIMIT200.name();
    }

    @Override
    public String mask(String value) {
        String masked = MaskUtils.maskByPercent(value, 1);
        return MaskUtils.limit(masked, 200);
    }
}
