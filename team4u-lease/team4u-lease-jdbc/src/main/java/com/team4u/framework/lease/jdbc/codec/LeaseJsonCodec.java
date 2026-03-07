package com.team4u.framework.lease.jdbc.codec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 租赁任务属性的 JSON 编解码器
 * <p>
 * 由于属性仅为简单的 Map&lt;String, String&gt;，此处使用轻量级的手动解析以减少依赖。
 *
 * @author jay.wu
 */
public class LeaseJsonCodec {

    /**
     * 将属性 Map 转换为 JSON 字符串
     *
     * @param attributes 属性 Map
     * @return JSON 字符串
     */
    public String toJson(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return "{}";
        }
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            if (!first) {
                builder.append(",");
            }
            first = false;
            builder.append("\"").append(escape(entry.getKey())).append("\":");
            builder.append("\"").append(escape(entry.getValue())).append("\"");
        }
        builder.append("}");
        return builder.toString();
    }

    /**
     * 将 JSON 字符串解析为属性 Map
     *
     * @param json JSON 字符串
     * @return 属性 Map
     */
    public Map<String, String> fromJson(String json) {
        if (json == null || json.trim().isEmpty() || "{}".equals(json.trim())) {
            return Collections.emptyMap();
        }
        String content = trimBraces(json.trim());
        if (content.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new LinkedHashMap<String, String>();
        int index = 0;
        while (index < content.length()) {
            index = skipWhitespace(content, index);
            if (index >= content.length()) {
                break;
            }
            ParsedToken keyToken = readString(content, index);
            index = skipWhitespace(content, keyToken.nextIndex);
            if (index >= content.length() || content.charAt(index) != ':') {
                throw new IllegalArgumentException("Invalid attributes json: missing ':'");
            }
            index = skipWhitespace(content, index + 1);
            ParsedToken valueToken = readString(content, index);
            result.put(keyToken.value, valueToken.value);
            index = skipWhitespace(content, valueToken.nextIndex);
            if (index < content.length() && content.charAt(index) == ',') {
                index++;
            }
        }
        return result;
    }

    private String trimBraces(String value) {
        if (value.startsWith("{") && value.endsWith("}")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private ParsedToken readString(String value, int start) {
        if (start >= value.length() || value.charAt(start) != '"') {
            throw new IllegalArgumentException("Invalid attributes json: missing opening quote");
        }
        StringBuilder builder = new StringBuilder();
        int index = start + 1;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current == '\\') {
                if (index + 1 >= value.length()) {
                    throw new IllegalArgumentException("Invalid attributes json: dangling escape");
                }
                char escaped = value.charAt(index + 1);
                switch (escaped) {
                    case '\\':
                    case '"':
                    case '/':
                        builder.append(escaped);
                        break;
                    case 'b':
                        builder.append('\b');
                        break;
                    case 'f':
                        builder.append('\f');
                        break;
                    case 'n':
                        builder.append('\n');
                        break;
                    case 'r':
                        builder.append('\r');
                        break;
                    case 't':
                        builder.append('\t');
                        break;
                    default:
                        throw new IllegalArgumentException("Invalid attributes json escape: \\" + escaped);
                }
                index += 2;
                continue;
            }
            if (current == '"') {
                return new ParsedToken(builder.toString(), index + 1);
            }
            builder.append(current);
            index++;
        }
        throw new IllegalArgumentException("Invalid attributes json: missing closing quote");
    }

    private int skipWhitespace(String value, int index) {
        int next = index;
        while (next < value.length() && Character.isWhitespace(value.charAt(next))) {
            next++;
        }
        return next;
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            switch (current) {
                case '\\':
                case '"':
                    builder.append('\\').append(current);
                    break;
                case '\b':
                    builder.append("\\b");
                    break;
                case '\f':
                    builder.append("\\f");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                default:
                    builder.append(current);
                    break;
            }
        }
        return builder.toString();
    }

    private static final class ParsedToken {
        private final String value;
        private final int nextIndex;

        private ParsedToken(String value, int nextIndex) {
            this.value = value;
            this.nextIndex = nextIndex;
        }
    }
}
