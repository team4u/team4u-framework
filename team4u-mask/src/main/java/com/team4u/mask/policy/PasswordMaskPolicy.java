package com.team4u.mask.policy;

import com.team4u.mask.MaskPolicy;
import com.team4u.mask.MaskType;

/**
 * 密码脱敏策略
 */
public class PasswordMaskPolicy implements MaskPolicy {

    @Override
    public String key() {
        return MaskType.PASSWORD.name();
    }

    @Override
    public String mask(String value) {
        return "******";
    }
}
