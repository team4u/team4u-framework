package com.team4u.framework.mask.policy;

import com.team4u.framework.mask.MaskPolicy;
import com.team4u.framework.mask.MaskType;
import com.team4u.framework.mask.MaskUtils;

/**
 * 身份证号脱敏策略 (保留前5后2)
 *
 * @author jay.wu
 */
public class IdCardNoMaskPolicy implements MaskPolicy {

    @Override
    public String key() {
        return MaskType.ID_CARD_NO.name();
    }

    @Override
    public String mask(String value) {
        return MaskUtils.mask(value, 5, 2);
    }
}
