package com.team4u.criterion.model.convert;

/**
 * 字符串转换器
 * <p>
 * 支持将对象显式转换为字符串
 *
 * @author jay.wu
 */
public class StringValueConverter implements ValueConverter {

    @Override
    public String id() {
        return "string";
    }

    @Override
    public Comparable<?> apply(Object obj) {
        return obj == null ? null : String.valueOf(obj);
    }
}
