package com.team4u.log.mask.policy;

import com.team4u.log.mask.MaskPolicy;
import com.team4u.log.mask.MaskType;
import com.team4u.log.mask.MaskUtils;

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
