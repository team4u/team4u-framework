package com.team4u.framework.mask.policy;

import com.team4u.framework.mask.MaskPolicy;
import com.team4u.framework.mask.MaskType;

/**
 * 不进行脱敏策略 (返回明文)
 *
 * @author jay.wu
 */
public class NoneMaskPolicy implements MaskPolicy {

    @Override
    public String key() {
        return MaskType.NONE.name();
    }

    @Override
    public String mask(String value) {
        return value;
    }
}
