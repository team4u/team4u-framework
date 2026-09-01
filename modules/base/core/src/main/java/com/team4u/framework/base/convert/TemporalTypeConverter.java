package com.team4u.framework.base.convert;

import com.team4u.framework.base.util.DateUtil;

import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * 时间类型转换器
 * <p>
 * 支持 Date、LocalDate、LocalDateTime、Instant、Duration 以及时间戳字符串的相互转换。
 *
 * @author jay.wu
 */
final class TemporalTypeConverter extends AbstractTypeConverter {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public boolean supports(Type targetType, Object source) {
        Class<?> type = toClass(targetType);
        return type == Date.class
                || type == LocalDate.class
                || type == LocalDateTime.class
                || type == Instant.class
                || type == Duration.class;
    }

    @Override
    public Object convert(Type targetType, Object source) {
        Class<?> type = toClass(targetType);
        if (type == Date.class) {
            return toDate(source);
        }
        if (type == LocalDate.class) {
            return toLocalDate(source);
        }
        if (type == LocalDateTime.class) {
            return toLocalDateTime(source);
        }
        if (type == Instant.class) {
            return toInstant(source);
        }
        if (type == Duration.class) {
            return ConvertUtil.toDuration(source);
        }
        return null;
    }

    /**
     * 转换为 Date 对象
     *
     * @param source 原始值，支持 Date、时间戳、日期字符串
     * @return Date 对象
     */
    private Date toDate(Object source) {
        if (source instanceof Date) {
            return (Date) source;
        }
        if (source instanceof Number) {
            return new Date(((Number) source).longValue());
        }
        Long epochMillis = ConvertUtil.toLong(source);
        if (epochMillis != null && source.toString().trim().matches("-?\\d+")) {
            return new Date(epochMillis);
        }
        return DateUtil.parse(ConvertUtil.toStr(source));
    }

    /**
     * 转换为 LocalDate 对象
     *
     * @param source 原始值，支持 LocalDate、Date、日期字符串
     * @return LocalDate 对象
     */
    private LocalDate toLocalDate(Object source) {
        if (source instanceof LocalDate) {
            return (LocalDate) source;
        }
        if (source instanceof Date) {
            return ((Date) source).toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        }
        String value = ConvertUtil.toStr(source, "").trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(value, DATE_FORMATTER);
        } catch (Exception ignored) {
            Date date = toDate(source);
            return date == null ? null : date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        }
    }

    /**
     * 转换为 LocalDateTime 对象
     *
     * @param source 原始值，支持 LocalDateTime、Date、日期时间字符串
     * @return LocalDateTime 对象
     */
    private LocalDateTime toLocalDateTime(Object source) {
        if (source instanceof LocalDateTime) {
            return (LocalDateTime) source;
        }
        if (source instanceof Date) {
            return LocalDateTime.ofInstant(((Date) source).toInstant(), ZoneId.systemDefault());
        }
        String value = ConvertUtil.toStr(source, "").trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, DATE_TIME_FORMATTER);
        } catch (Exception ignored) {
            Date date = toDate(source);
            return date == null ? null : LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
        }
    }

    /**
     * 转换为 Instant 对象
     *
     * @param source 原始值，支持 Instant、时间戳、Date
     * @return Instant 对象
     */
    private Instant toInstant(Object source) {
        if (source instanceof Instant) {
            return (Instant) source;
        }
        if (source instanceof Number) {
            return Instant.ofEpochMilli(((Number) source).longValue());
        }
        Date date = toDate(source);
        return date == null ? null : date.toInstant();
    }

    @Override
    public int order() {
        return 30;
    }
}
