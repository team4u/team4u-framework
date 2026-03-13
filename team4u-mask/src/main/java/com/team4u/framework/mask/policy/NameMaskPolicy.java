package com.team4u.framework.mask.policy;

import com.team4u.framework.mask.MaskPolicy;
import com.team4u.framework.mask.MaskType;
import com.team4u.framework.mask.MaskUtils;

/**
 * 姓名掩码器
 * <p>
 * - 中文姓名：三个字及以下，只显示最后一个字;三个字以上，显示最后两个字，如：*明、*小明
 * <p>
 * - 英文姓名：只显示前一后一，如：f***y
 *
 * @author jay.wu
 */
public class NameMaskPolicy implements MaskPolicy {

    @Override
    public String key() {
        return MaskType.NAME.name();
    }

    @Override
    public String mask(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }

        if (value.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)) {
            return maskChineseName(value);
        }

        return maskEnglishName(value);
    }

    private String maskChineseName(String value) {
        int length = value.length();
        if (length <= 3) {
            // 三个字及以下，只显示最后一个字
            return MaskUtils.mask(value, 0, 1);
        } else {
            // 三个字以上，显示最后两个字
            return MaskUtils.mask(value, 0, 2);
        }
    }

    private String maskEnglishName(String value) {
        // 英文姓名：只显示前一后一
        return MaskUtils.mask(value, 1, 1);
    }
}
