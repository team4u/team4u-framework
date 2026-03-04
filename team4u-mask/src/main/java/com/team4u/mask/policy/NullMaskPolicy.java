package com.team4u.mask.policy;

import com.team4u.mask.MaskPolicy;
import com.team4u.mask.MaskType;

/**
 * 固定为 null 的脱敏策略
 *
 * @author jay.wu
 */
public class NullMaskPolicy implements MaskPolicy {

    @Override
    public String key() {
        return MaskType.NULL.name();
    }

    @Override
    public String mask(String value) {
        return null;
    }
}
