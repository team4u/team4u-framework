package com.team4u.framework.flow.graph;

import com.team4u.framework.flow.api.Retry;

import java.time.Duration;

/**
 * 流程图渲染通用格式化与文本处理工具类。
 *
 * @author jay.wu
 */
final class FlowGraphFormatters {
    private FlowGraphFormatters() { }

    /**
     * 稳定配置摘要：针对 Retry 与 Duration 生成确定性文本摘要，避免直接 toString。
     */
    static String configurationSummary(Object configuration) {
        if (configuration instanceof Retry) {
            Retry retry = (Retry) configuration;
            return "maxAttempts=" + retry.maxAttempts()
                    + ",backoff=" + durationSummary(retry.backoff());
        }
        if (configuration instanceof Duration) {
            return "timeout=" + durationSummary((Duration) configuration);
        }
        return "<none>";
    }

    /**
     * 纳秒级确定性时长摘要。
     */
    static String durationSummary(Duration duration) {
        return duration.getSeconds() + "s" + duration.getNano() + "ns";
    }

    /**
     * 空字符串安全回退。
     */
    static String display(String value) {
        return value == null ? "<unnamed>" : value;
    }

    /**
     * 转义标准控制字符与双引号。
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
}
