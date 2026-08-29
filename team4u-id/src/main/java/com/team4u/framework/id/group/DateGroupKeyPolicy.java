package com.team4u.framework.id.group;

import com.team4u.framework.id.api.SeqConfigException;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 日期时间分组策略（内置）
 * <p>
 * 按时间格式生成分组标识，实现周期重置：每天（{@code yyyyMMdd}）、
 * 每月（{@code yyyyMM}）、每年（{@code yyyy}）等。时间源取上下文时钟，
 * 测试可注入虚拟时钟精确推进周期切换。
 * </p>
 *
 * @author jay.wu
 */
public class DateGroupKeyPolicy implements GroupKeyPolicy {

    public static final String KEY = "DATE";

    public static final DateGroupKeyPolicy INSTANCE = new DateGroupKeyPolicy();

    /**
     * 格式化器按模式缓存（DateTimeFormatter 线程安全且构造昂贵）
     */
    private static final Map<String, DateTimeFormatter> FORMATTERS = new ConcurrentHashMap<>();

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String groupKey(Context context) {
        SeqGroupConfig config = context.getConfig();
        String pattern = config.getFormat() == null || config.getFormat().trim().isEmpty()
                ? "yyyyMMdd" : config.getFormat().trim();
        DateTimeFormatter formatter = FORMATTERS.computeIfAbsent(pattern, p -> {
            try {
                return DateTimeFormatter.ofPattern(p);
            } catch (IllegalArgumentException e) {
                throw new SeqConfigException("Invalid date group format|format=" + p, e);
            }
        });
        return formatter.format(ZonedDateTime.now(context.getClock()));
    }
}
