package com.team4u.mask.policy;

import com.team4u.mask.MaskPolicy;
import com.team4u.mask.MaskType;
import com.team4u.mask.MaskUtils;

/**
 * 全部脱敏策略 (固定为*)
 *
 * @author jay.wu
 */
public class HideMaskPolicy implements MaskPolicy {

    @Override
    public String key() {
        return MaskType.HIDE.name();
    }

    @Override
    public String mask(String value) {
        return MaskUtils.hide(value);
    }
}
