package com.team4u.log.mask.policy;

import com.team4u.log.mask.MaskPolicy;
import com.team4u.log.mask.MaskType;
import com.team4u.log.mask.MaskUtils;

/**
 * 仅显示前1后1
 *
 * @author jay.wu
 */
public class B1A1MaskPolicy implements MaskPolicy {

    @Override
    public String key() {
        return MaskType.B1A1.name();
    }

    @Override
    public String mask(String value) {
        return MaskUtils.mask(value, 1, 1);
    }
}
