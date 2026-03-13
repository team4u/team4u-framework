package com.team4u.framework.base.util;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通用的文本模板解析器
 * <p>
 * 支持 {@code ${property}} 格式的占位符解析。数据由调用方通过 {@link Map} 或值提供者函数传入。
 * </p>
 *
 * @author jay.wu
 */
public class TextTemplate {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{(.+?)}");

    private final String template;
    private final List<Segment> segments;
    private final Set<String> variableNames;

    /**
     * 构造文本模板
     *
     * @param template 原始模板字符串
     */
    public TextTemplate(String template) {
        this.template = template;
        this.variableNames = new LinkedHashSet<>();
        this.segments = parseSegments(template);
    }

    /**
     * 判断是否为动态模板
     *
     * @return 若包含占位符则返回 {@code true}
     */
    public boolean isDynamic() {
        return !variableNames.isEmpty();
    }

    /**
     * 获取模板中定义的所有变量名
     *
     * @return 变量名集合（保持在模板中出现的顺序）
     */
    public Set<String> getVariableNames() {
        return Collections.unmodifiableSet(variableNames);
    }

    /**
     * 通过 Map 渲染模板
     *
     * @param context 包含变量名与对应值的 Map
     * @return 渲染后的文本内容
     */
    public String render(Map<String, ?> context) {
        return render(context == null ? null : context::get);
    }

    /**
     * 通过自定义值提供者渲染模板
     *
     * @param valueProvider 根据变量名返回对应值的函数
     * @return 渲染后的文本内容
     */
    public String render(Function<String, Object> valueProvider) {
        if (!isDynamic() || valueProvider == null) {
            return template;
        }

        StringBuilder sb = new StringBuilder();
        for (Segment segment : segments) {
            sb.append(segment.getValue(valueProvider));
        }
        return sb.toString();
    }

    /**
     * 将模板解析为多个文本段（静态或动态）
     *
     * @param template 原始模板
     * @return 解析后的段列表
     */
    private List<Segment> parseSegments(String template) {
        List<Segment> segments = new ArrayList<>();
        if (template == null) {
            return segments;
        }

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        int lastEnd = 0;
        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                segments.add(new LiteralSegment(template.substring(lastEnd, matcher.start())));
            }
            String propertyName = matcher.group(1);
            variableNames.add(propertyName);
            segments.add(new PlaceholderSegment(propertyName, matcher.group(0)));
            lastEnd = matcher.end();
        }

        if (lastEnd < template.length()) {
            segments.add(new LiteralSegment(template.substring(lastEnd)));
        }
        return segments;
    }

    @Override
    public String toString() {
        return template;
    }

    /**
     * 模板段接口
     */
    private interface Segment {
        /**
         * 获取当前段的渲染值
         *
         * @param valueProvider 值提供者
         * @return 渲染后的字符串
         */
        String getValue(Function<String, Object> valueProvider);
    }

    /**
     * 静态文本段实现
     */
    private static class LiteralSegment implements Segment {
        private final String text;

        public LiteralSegment(String text) {
            this.text = text;
        }

        @Override
        public String getValue(Function<String, Object> valueProvider) {
            return text;
        }
    }

    /**
     * 动态占位符段实现
     */
    private static class PlaceholderSegment implements Segment {
        private final String propertyName;
        private final String originalText;

        public PlaceholderSegment(String propertyName, String originalText) {
            this.propertyName = propertyName;
            this.originalText = originalText;
        }

        @Override
        public String getValue(Function<String, Object> valueProvider) {
            Object value = valueProvider.apply(propertyName);
            // 若找不到对应值，则保留原始占位符文本
            return value != null ? value.toString() : originalText;
        }
    }
}
