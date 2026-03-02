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
        if (value == null || !value.contains("@")) {
            return value;
        }
        int index = value.indexOf("@");
        // 邮箱前缀长度小于等于 1 时处理
        if (index <= 1) {
            return "*" + value.substring(index);
        }
        // 保留前缀第一个字符并脱敏，拼接域名
        return value.charAt(0) + "****" + value.substring(index);
    }
}
