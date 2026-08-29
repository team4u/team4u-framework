package com.team4u.it;

import com.team4u.framework.serializer.json.JsonSerializerPolicy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal hand-written JSON policy for the ratelimiter consumer fixture.
 * <p>
 * Proves the documented no-Jackson contract of team4u-ratelimiter-core: the
 * application provides its own {@link JsonSerializerPolicy} via ServiceLoader
 * (see META-INF/services), so rule JSON parsing works while the runtime tree
 * carries neither Jackson nor team4u-serializer-jackson. This parser only
 * supports the flat object/array shapes used by rate limit rules.
 */
public class MiniJsonPolicy implements JsonSerializerPolicy {

    @Override
    public String key() {
        return "consumer-mini-json";
    }

    @Override
    public boolean supports(Void context) {
        return true;
    }

    @Override
    public String toJsonStr(Object obj) {
        throw new UnsupportedOperationException("fixture policy: not needed by this consumer");
    }

    @Override
    public <T> T toBean(String json, Class<T> clazz) {
        throw new UnsupportedOperationException("fixture policy: not needed by this consumer");
    }

    @Override
    public <T> T toBean(String json, java.lang.reflect.Type type) {
        throw new UnsupportedOperationException("fixture policy: not needed by this consumer");
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> toList(String json, Class<T> clazz) {
        List<Object> items = parseArray(json);
        List<T> result = new ArrayList<T>();
        for (Object item : items) {
            result.add((T) bindObject((java.util.Map<String, Object>) item, clazz));
        }
        return result;
    }

    @Override
    public Object parseObj(String json) {
        throw new UnsupportedOperationException("fixture policy: not needed by this consumer");
    }

    // --- minimal flat JSON parser (objects, arrays, strings, numbers, booleans) ---

    private final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        Object parseValue() {
            skipWhitespace();
            char c = peek();
            if (c == '{') {
                return parseObject();
            }
            if (c == '[') {
                return parseArray();
            }
            if (c == '"') {
                return parseString();
            }
            if (c == 't' || c == 'f') {
                return parseBoolean();
            }
            return parseNumber();
        }

        List<Object> parseArray() {
            expect('[');
            List<Object> items = new ArrayList<Object>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return items;
            }
            while (true) {
                items.add(parseValue());
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    return items;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("bad array at " + pos);
                }
            }
        }

        java.util.Map<String, Object> parseObject() {
            expect('{');
            java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<String, Object>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("bad object at " + pos);
                }
            }
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = text.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char escaped = text.charAt(pos++);
                    if (escaped == 'u') {
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    } else if (escaped == 'n') {
                        sb.append('\n');
                    } else {
                        sb.append(escaped);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Boolean parseBoolean() {
            if (text.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("bad boolean at " + pos);
        }

        Number parseNumber() {
            int start = pos;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                    pos++;
                } else {
                    break;
                }
            }
            String raw = text.substring(start, pos);
            if (raw.contains(".") || raw.contains("e") || raw.contains("E")) {
                return Double.parseDouble(raw);
            }
            return Long.parseLong(raw);
        }

        void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        char peek() {
            return text.charAt(pos);
        }

        char next() {
            return text.charAt(pos++);
        }

        void expect(char expected) {
            char c = next();
            if (c != expected) {
                throw new IllegalArgumentException("expected " + expected + " but got " + c + " at " + pos);
            }
        }
    }

    private List<Object> parseArray(String json) {
        return new Parser(json).parseArray();
    }

    private static <T> T bindObject(java.util.Map<String, Object> values, Class<T> clazz) {
        try {
            Constructor<T> constructor = clazz.getDeclaredConstructor();
            constructor.setAccessible(true);
            T instance = constructor.newInstance();
            for (Field field : clazz.getDeclaredFields()) {
                Object value = values.get(field.getName());
                if (value == null) {
                    continue;
                }
                field.setAccessible(true);
                Class<?> type = field.getType();
                if (type == String.class) {
                    field.set(instance, String.valueOf(value));
                } else if (type == long.class || type == Long.class) {
                    field.set(instance, ((Number) value).longValue());
                } else if (type == int.class || type == Integer.class) {
                    field.set(instance, ((Number) value).intValue());
                } else if (type == boolean.class || type == Boolean.class) {
                    field.set(instance, value);
                } else if (type == double.class || type == Double.class) {
                    field.set(instance, ((Number) value).doubleValue());
                } else {
                    field.set(instance, value);
                }
            }
            return instance;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot bind " + clazz.getName(), e);
        }
    }
}
