package com.team4u.framework.mask.policy;

import com.team4u.framework.mask.MaskPolicy;
import com.team4u.framework.mask.MaskType;
import com.team4u.framework.mask.MaskUtils;

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
