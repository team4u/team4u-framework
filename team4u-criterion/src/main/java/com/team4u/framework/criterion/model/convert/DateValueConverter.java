package com.team4u.framework.criterion.model.convert;

import com.team4u.framework.base.util.DateUtil;

import java.util.Date;

/**
 * 日期转换器
 * <p>
 * 支持将日期对象、日期字符串或 "now" 语义转换为 Date 对象
 *
 * @author jay.wu
 */
public class DateValueConverter implements ValueConverter {

    @Override
    public String key() {
        return "date";
    }

    @Override
    public Comparable<?> apply(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof Date) {
            return (Date) obj;
        }
        // 支持 "now" 语义
        if ("now".equalsIgnoreCase(String.valueOf(obj))) {
            return new Date();
        }
        try {
            return DateUtil.parse(String.valueOf(obj));
        } catch (Exception e) {
            return null;
        }
    }
}
