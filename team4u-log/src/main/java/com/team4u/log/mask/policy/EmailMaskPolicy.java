package com.team4u.log.mask.policy;

import com.team4u.log.mask.MaskPolicy;
import com.team4u.log.mask.MaskType;
import com.team4u.log.mask.MaskUtils;

/**
 * 电子邮箱脱敏策略
 *
 * @author jay.wu
 */
public class EmailMaskPolicy implements MaskPolicy {

    @Override
    public String key() {
        return MaskType.EMAIL.name();
    }

    @Override
    public String mask(String value) {
        return MaskUtils.maskEmail(value);
    }
}
