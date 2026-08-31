package com.team4u.framework.flow.diagram;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;

/**
 * 流程图表渲染工具类。
 *
 * @author jay.wu
 */
final class FlowDiagramFormatters {

    private static final String OPAQUE = "<opaque>";

    private FlowDiagramFormatters() {
    }

    static String display(String value) {
        return value == null || value.trim().isEmpty() ? "<none>" : value;
    }

    static String simpleClassName(Class<?> clazz) {
        if (clazz == null) return "<none>";
        String name = clazz.getSimpleName();
        return name.isEmpty() ? clazz.getName() : name;
    }

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

    static String durationSummary(Duration duration) {
        if (duration == null) {
            return "<none>";
        }
        return "timeout=" + duration.getSeconds() + "s" + duration.getNano() + "ns";
    }

    static String configurationSummary(Object configuration) {
        if (configuration == null) {
            return "<none>";
        }
        if (configuration instanceof Duration) {
            return durationSummary((Duration) configuration);
        }
        return "<none>";
    }

    static String stableConstant(Object key) {
        if (key == null) {
            return "null";
        }
        if (key instanceof String
                || key instanceof Number
                || key instanceof Boolean
                || key instanceof Character
                || key instanceof Enum<?>
                || key instanceof BigInteger
                || key instanceof BigDecimal) {
            return String.valueOf(key);
        }
        return OPAQUE;
    }

    static String escapeText(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                case '|':
                    escaped.append("\\|");
                    break;
                default:
                    escaped.append(c);
                    break;
            }
        }
        return escaped.toString();
    }

    static String escapeMermaid(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"':
                    escaped.append("&quot;");
                    break;
                case '\\':
                    escaped.append("&#92;");
                    break;
                case '\n':
                    escaped.append("<br/>");
                    break;
                case '\r':
                    break;
                case '|':
                    escaped.append("&#124;");
                    break;
                case '[':
                    escaped.append("&#91;");
                    break;
                case ']':
                    escaped.append("&#93;");
                    break;
                case '{':
                    escaped.append("&#123;");
                    break;
                case '}':
                    escaped.append("&#125;");
                    break;
                case '(':
                    escaped.append("&#40;");
                    break;
                case ')':
                    escaped.append("&#41;");
                    break;
                case '<':
                    escaped.append("&lt;");
                    break;
                case '>':
                    escaped.append("&gt;");
                    break;
                case '`':
                    escaped.append("&#96;");
                    break;
                case '#':
                    escaped.append("&#35;");
                    break;
                case '&':
                    escaped.append("&amp;");
                    break;
                default:
                    escaped.append(c);
                    break;
            }
        }
        return escaped.toString();
    }
}
