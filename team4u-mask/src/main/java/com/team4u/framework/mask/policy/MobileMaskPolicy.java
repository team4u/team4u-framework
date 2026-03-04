package com.team4u.framework.mask.policy;

import com.team4u.framework.mask.MaskPolicy;
import com.team4u.framework.mask.MaskType;
import com.team4u.framework.mask.MaskUtils;

/**
 * 手机号脱敏策略 (保留前3后3)
 *
 * @author jay.wu
 */
public class MobileMaskPolicy implements MaskPolicy {

    @Override
    public String key() {
        return MaskType.MOBILE.name();
    }

    @Override
    public String mask(String value) {
        return MaskUtils.mask(value, 3, 3);
    }
}
