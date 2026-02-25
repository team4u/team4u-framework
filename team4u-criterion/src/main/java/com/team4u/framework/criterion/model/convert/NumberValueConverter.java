package com.team4u.framework.criterion.model.convert;

import com.team4u.framework.criterion.util.FastNumberUtil;

/**
 * 数值转换器
 * <p>
 * 使用极速数字转换替代原先的 BigDecimal
 *
 * @author jay.wu
 */
public class NumberValueConverter implements ValueConverter {

    @Override
    public String key() {
        return "number";
    }

    @Override
    public Comparable<?> apply(Object obj) {
        if (obj == null) {
            return null;
        }
        Number num = FastNumberUtil.toNumber(obj);
        if (num == null) {
            throw new NumberFormatException("无效的数字格式: " + obj);
        }
        return (Comparable<?>) num;
    }
}
