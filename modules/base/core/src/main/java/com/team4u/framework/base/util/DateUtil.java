package com.team4u.framework.base.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * 日期工具类
 * <p>
 * 基于 Java 8 时间 API 提供各种日期处理的便捷方法，包括日期格式化、解析、偏移计算以及简易耗时统计。
 *
 * @author jay.wu
 */
public class DateUtil {

    /**
     * 默认日期时间格式模板
     */
    private static final String DEFAULT_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /**
     * 获取当前日期的字符串形式
     * <p>
     * 使用默认模板格式化：yyyy-MM-dd HH:mm:ss
     *
     * @return 当前日期时间字符串
     */
    public static String now() {
        return format(new Date(), DEFAULT_PATTERN);
    }

    /**
     * 格式化日期对象为字符串
     *
     * @param date    待格式化的 Date 对象
     * @param pattern 格式化模板
     * @return 格式化后的日期字符串，若日期对象为 null 则返回 null
     */
    public static String format(Date date, String pattern) {
        if (date == null) {
            return null;
        }
        return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern(pattern));
    }

    /**
     * 解析日期字符串为 Date 对象
     *
     * @param dateStr 遵循指定模板的日期字符串
     * @param pattern 格式化模板
     * @return 解析后的 Date 实例，若字符串为空则返回 null
     */
    public static Date parse(String dateStr, String pattern) {
        if (dateStr == null) {
            return null;
        }
        LocalDateTime ldt = LocalDateTime.parse(dateStr, DateTimeFormatter.ofPattern(pattern));
        return Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant());
    }

    /**
     * 自动解析日期字符串
     * <p>
     * 支持自动识别 "yyyy-MM-dd HH:mm:ss"（长度大于 10）和 "yyyy-MM-dd"（长度小于等于 10）两种常见格式。
     *
     * @param dateStr 待解析的日期字符串
     * @return 解析后的 Date 实例，若解析失败或输入为空白字符串则返回 null
     */
    public static Date parse(String dateStr) {
        if (StringUtil.isBlank(dateStr)) {
            return null;
        }
        String pattern = dateStr.length() > 10 ? DEFAULT_PATTERN : "yyyy-MM-dd";
        try {
            if (dateStr.length() <= 10) {
                return Date.from(LocalDate.parse(dateStr, DateTimeFormatter.ofPattern(pattern))
                        .atStartOfDay(ZoneId.systemDefault()).toInstant());
            }
            return parse(dateStr, pattern);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 偏移日期（按天偏移）
     * <p>
     * 在给定日期基础上增加或减少指定天数。
     *
     * @param date   原始日期
     * @param offset 偏移天数，正数表示向未来偏移，负数表示向过去偏移
     * @return 偏移后的 Date 实例，若输入日期为 null 则返回 null
     */
    public static Date offsetDay(Date date, int offset) {
        if (date == null) {
            return null;
        }
        LocalDateTime ldt = LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
        return Date.from(ldt.plusDays(offset).atZone(ZoneId.systemDefault()).toInstant());
    }

    /**
     * 创建并启动一个简易计时器
     *
     * @return {@link TimeInterval} 计时器实例
     */
    public static TimeInterval timer() {
        return new TimeInterval();
    }

    /**
     * 简易计时器，用于耗时统计
     */
    public static class TimeInterval {
        /**
         * 启动时刻的系统毫秒数
         */
        private final long start;

        /**
         * 构造计时器并自动记录当前开始时间
         */
        public TimeInterval() {
            this.start = System.currentTimeMillis();
        }

        /**
         * 获取自计时器创建起经过的毫秒数
         *
         * @return 耗时毫秒数
         */
        public long interval() {
            return System.currentTimeMillis() - start;
        }
    }
}
