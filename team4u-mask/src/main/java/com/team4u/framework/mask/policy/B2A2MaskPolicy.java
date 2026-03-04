package com.team4u.framework.mask.policy;

import com.team4u.framework.mask.MaskPolicy;
import com.team4u.framework.mask.MaskType;
import com.team4u.framework.mask.MaskUtils;

/**
 * 仅显示前2后2
 *
 * @author jay.wu
 */
public class B2A2MaskPolicy implements MaskPolicy {

    @Override
    public String key() {
        return MaskType.B2A2.name();
    }

    @Override
    public String mask(String value) {
        return MaskUtils.mask(value, 2, 2);
    }
}
