package com.team4u.framework.criterion.model.convert;

import com.team4u.framework.base.util.CollectionUtil;

/**
 * 大小/长度转换器
 * <p>
 * 支持：Collection, Map, Array, String
 *
 * @author jay.wu
 */
public class SizeValueConverter implements ValueConverter {

    @Override
    public String key() {
        return "size";
    }

    @Override
    public Comparable<?> apply(Object obj) {
        if (obj == null) {
            return 0;
        }

        if (obj instanceof CharSequence) {
            return ((CharSequence) obj).length();
        }

        try {
            return CollectionUtil.size(obj);
        } catch (Exception e) {
            return 0;
        }
    }
}