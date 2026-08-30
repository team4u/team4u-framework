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
        int length = MaskUtils.codePointLength(value);
        if (length <= 3) {
            // 针对短字符中文姓名（1~3个字）：隐藏姓氏及中间字，默认尽全力保留尾字
            return MaskUtils.mask(value, 0, 1);
        } else {
            // 针对长字符中文姓名（复姓或少数民族，4个字及以上）：隐藏首部，保留尾部至少2个字符段
            return MaskUtils.mask(value, 0, 2);
        }
    }

    private String maskEnglishName(String value) {
        // 针对英文或拼音姓名：采取仅暴漏首尾单一有效字母，隐藏中间全部内容的策略
        return MaskUtils.mask(value, 1, 1);
    }
}
