package com.team4u.framework.mask.policy;

import com.team4u.framework.mask.MaskPolicy;
import com.team4u.framework.mask.MaskType;

import com.team4u.framework.mask.MaskUtils;

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
        if (value == null || !value.contains("@")) {
            return value;
        }
        int index = value.indexOf("@");
        String prefix = value.substring(0, index);
        String suffix = value.substring(index);
        // 针对单字符及超短前缀的安全脱敏策略
        if (MaskUtils.codePointLength(prefix) <= 1) {
            return "*" + suffix;
        }
        // 对于多字符前缀：暴露首部单一有效CodePoint，隐蔽中间内容，并拼接完整安全防护域
        return MaskUtils.substringByCodePoints(prefix, 0, 1) + "****" + suffix;
    }
}
