package com.team4u.framework.flow.graph;

import com.team4u.framework.flow.desc.NodeDescription;
import com.team4u.framework.flow.spi.BindingDescriptor;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;

/**
 * 流程图渲染通用格式化与文本处理工具类。
 *
 * @author jay.wu
 */
final class FlowGraphFormatters {
    private FlowGraphFormatters() { }

    /**
     * 提取 Class 的简短类名（去除包名，处理内部类与合成类）。
     */
    static String simpleClassName(Class<?> clazz) {
        if (clazz == null) return "<unresolved>";
        String simpleName = clazz.getSimpleName();
        if (simpleName != null && !simpleName.isEmpty()) {
            return simpleName;
        }
        String fullName = clazz.getName();
        int lastDot = Math.max(fullName.lastIndexOf('.'), fullName.lastIndexOf('$'));
        return lastDot >= 0 ? fullName.substring(lastDot + 1) : fullName;
    }

    /**
     * 人类友好的确定性时长摘要（如 2s, 500ms, 1s500ms）。
     */
    static String durationFriendly(Duration duration) {
        if (duration == null) return "<none>";
        long seconds = duration.getSeconds();
        int nanos = duration.getNano();
        if (nanos == 0) {
            return seconds + "s";
        }
        if (seconds == 0 && nanos % 1_000_000 == 0) {
            return (nanos / 1_000_000) + "ms";
        }
        return seconds + "s" + nanos + "ns";
    }

    /**
     * 稳定配置摘要：针对 Duration 生成确定性文本摘要。
     */
    static String configurationSummary(Object configuration) {
        if (configuration instanceof Duration) {
            return "timeout=" + durationSummary((Duration) configuration);
        }
        return "<none>";
    }

    /**
     * 纳秒级确定性时长摘要。
     */
    static String durationSummary(Duration duration) {
        if (duration == null) return "<none>";
        return duration.getSeconds() + "s" + duration.getNano() + "ns";
    }

    /**
     * 空字符串安全回退。
     */
    static String display(String value) {
        return value == null ? "<unnamed>" : value;
    }

    /**
     * 节点通用元数据摘要（Kind + Path + Label）。
     */
    static String metadata(NodeDescription node) {
        return node.kind().name() + " | path=" + display(node.path())
                + " | label=" + (node.label().isPresent()
                ? display(node.label().get()) : "<none>");
    }

    /**
     * 依赖组件绑定信息格式化。
     */
    static String bindingSummary(NodeDescription node) {
        if (!node.binding().isPresent()) {
            return "";
        }
        BindingDescriptor binding = node.binding().get();
        String contract = binding.contractClass().isPresent()
                ? binding.contractClass().get().getName() : "<unresolved>";
        String qualifier = binding.qualifier().isPresent()
                ? display(binding.qualifier().get()) : "<none>";
        return " | binding=" + display(binding.kind()) + " contract=" + contract
                + " qualifier=" + qualifier;
    }

    /**
     * 稳定路由键/常量渲染：仅对不可子类化且 toString 确定的精确类型输出值，其余输出固定占位符。
     */
    static String stableConstant(Object value) {
        if (value == null) return "<null>";
        Class<?> type = value.getClass();
        if (type == String.class) {
            return display((String) value);
        }
        if (type == Integer.class || type == Long.class || type == Short.class
                || type == Byte.class || type == Character.class || type == Boolean.class
                || type == Float.class || type == Double.class) {
            return String.valueOf(value);
        }
        if (type == BigInteger.class || type == BigDecimal.class) {
            return value.toString();
        }
        if (value instanceof Enum<?>) {
            Enum<?> enumValue = (Enum<?>) value;
            return enumValue.getDeclaringClass().getName() + "." + enumValue.name();
        }
        if (type == Class.class) {
            Class<?> classValue = (Class<?>) value;
            return classValue.isSynthetic() ? "<opaque>" : classValue.getName();
        }
        return "<opaque>";
    }

    /**
     * 转义标准控制字符与双引号（用于 PlainText）。
     */
    static String escapeText(String value) {
        if (value == null) return "";
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\': escaped.append("\\\\"); break;
                case '"': escaped.append("\\\""); break;
                case '\n': escaped.append("\\n"); break;
                case '\r': escaped.append("\\r"); break;
                case '\t': escaped.append("\\t"); break;
                case '|': escaped.append("\\|"); break;
                default: escaped.append(character);
            }
        }
        return escaped.toString();
    }

    /**
     * Mermaid 专用 HTML/特殊字符安全转义。
     */
    static String escapeMermaid(String value) {
        if (value == null) return "";
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\': escaped.append("&#92;"); break;
                case '"': escaped.append("&quot;"); break;
                case '\n': escaped.append("<br/>"); break;
                case '\r': break;
                case '|': escaped.append("&#124;"); break;
                case '&': escaped.append("&amp;"); break;
                case '<': escaped.append("&lt;"); break;
                case '>': escaped.append("&gt;"); break;
                case '[': escaped.append("&#91;"); break;
                case ']': escaped.append("&#93;"); break;
                case '{': escaped.append("&#123;"); break;
                case '}': escaped.append("&#125;"); break;
                case '(': escaped.append("&#40;"); break;
                case ')': escaped.append("&#41;"); break;
                case '`': escaped.append("&#96;"); break;
                default: escaped.append(character);
            }
        }
        return escaped.toString();
    }
}
