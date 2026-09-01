package com.team4u.framework.flow.definition.type;

import com.team4u.framework.base.convert.ConvertUtil;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内置标准类型编解码器工厂（Type Codecs）。
 *
 * @author jay.wu
 */
public final class TypeCodecs {

    public static final TypeCodec<String> STRING = new TypeCodec<String>() {
        @Override
        public String decode(String literal) {
            if (literal == null) {
                return null;
            }
            // 去除首尾外层双引号（如果有）
            if (literal.length() >= 2 && literal.startsWith("\"") && literal.endsWith("\"")) {
                return literal.substring(1, literal.length() - 1);
            }
            return literal;
        }

        @Override
        public String encode(String value) {
            return value != null ? "\"" + value + "\"" : "";
        }
    };

    public static final TypeCodec<Boolean> BOOLEAN = new TypeCodec<Boolean>() {
        @Override
        public Boolean decode(String literal) {
            if ("true".equalsIgnoreCase(literal)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(literal)) {
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("Cannot decode boolean from: " + literal);
        }

        @Override
        public String encode(Boolean value) {
            return String.valueOf(value);
        }
    };

    public static final TypeCodec<Integer> INTEGER = new TypeCodec<Integer>() {
        @Override
        public Integer decode(String literal) {
            return Integer.parseInt(literal.trim());
        }

        @Override
        public String encode(Integer value) {
            return String.valueOf(value);
        }
    };

    public static final TypeCodec<Long> LONG = new TypeCodec<Long>() {
        @Override
        public Long decode(String literal) {
            String clean = literal.trim();
            if (clean.endsWith("L") || clean.endsWith("l")) {
                clean = clean.substring(0, clean.length() - 1);
            }
            return Long.parseLong(clean);
        }

        @Override
        public String encode(Long value) {
            return String.valueOf(value);
        }
    };

    public static final TypeCodec<Double> DOUBLE = new TypeCodec<Double>() {
        @Override
        public Double decode(String literal) {
            String clean = literal.trim();
            if (clean.endsWith("D") || clean.endsWith("d")) {
                clean = clean.substring(0, clean.length() - 1);
            }
            return Double.parseDouble(clean);
        }

        @Override
        public String encode(Double value) {
            return String.valueOf(value);
        }
    };

    public static final TypeCodec<Float> FLOAT = new TypeCodec<Float>() {
        @Override
        public Float decode(String literal) {
            String clean = literal.trim();
            if (clean.endsWith("F") || clean.endsWith("f")) {
                clean = clean.substring(0, clean.length() - 1);
            }
            return Float.parseFloat(clean);
        }

        @Override
        public String encode(Float value) {
            return String.valueOf(value);
        }
    };

    public static final TypeCodec<Short> SHORT = new TypeCodec<Short>() {
        @Override
        public Short decode(String literal) {
            return Short.parseShort(literal.trim());
        }

        @Override
        public String encode(Short value) {
            return String.valueOf(value);
        }
    };

    public static final TypeCodec<Byte> BYTE = new TypeCodec<Byte>() {
        @Override
        public Byte decode(String literal) {
            return Byte.parseByte(literal.trim());
        }

        @Override
        public String encode(Byte value) {
            return String.valueOf(value);
        }
    };

    public static final TypeCodec<Duration> DURATION = new TypeCodec<Duration>() {
        @Override
        public Duration decode(String literal) {
            return parseDuration(literal);
        }

        @Override
        public String encode(Duration value) {
            if (value == null) {
                return "";
            }
            long millis = value.toMillis();
            if (millis % 1000 == 0 && millis >= 1000) {
                return (millis / 1000) + "s";
            }
            return millis + "ms";
        }
    };

    private static final Map<Class<?>, TypeCodec<?>> BUILTINS;

    static {
        Map<Class<?>, TypeCodec<?>> map = new LinkedHashMap<Class<?>, TypeCodec<?>>();
        map.put(String.class, STRING);
        map.put(Boolean.class, BOOLEAN);
        map.put(boolean.class, BOOLEAN);
        map.put(Integer.class, INTEGER);
        map.put(int.class, INTEGER);
        map.put(Long.class, LONG);
        map.put(long.class, LONG);
        map.put(Double.class, DOUBLE);
        map.put(double.class, DOUBLE);
        map.put(Float.class, FLOAT);
        map.put(float.class, FLOAT);
        map.put(Short.class, SHORT);
        map.put(short.class, SHORT);
        map.put(Byte.class, BYTE);
        map.put(byte.class, BYTE);
        map.put(Duration.class, DURATION);
        BUILTINS = Collections.unmodifiableMap(map);
    }

    private TypeCodecs() { }

    /**
     * 为指定的枚举类型生成编解码器。
     *
     * @param enumClass 枚举 Class
     * @param <E>       枚举泛型
     * @return 枚举编解码器
     */
    @SuppressWarnings("unchecked")
    public static <E extends Enum<E>> TypeCodec<E> forEnum(final Class<E> enumClass) {
        return new TypeCodec<E>() {
            @Override
            public E decode(String literal) {
                if (literal == null) {
                    return null;
                }
                String clean = literal.trim();
                if (clean.startsWith("\"") && clean.endsWith("\"") && clean.length() >= 2) {
                    clean = clean.substring(1, clean.length() - 1);
                }
                try {
                    return Enum.valueOf(enumClass, clean);
                } catch (IllegalArgumentException ex) {
                    // 支持大小写不敏感匹配
                    for (E constant : enumClass.getEnumConstants()) {
                        if (constant.name().equalsIgnoreCase(clean)) {
                            return constant;
                        }
                    }
                    throw new IllegalArgumentException(
                            "Cannot decode enum " + enumClass.getSimpleName() + " from: " + literal, ex);
                }
            }

            @Override
            public String encode(E value) {
                return value != null ? value.name() : "";
            }
        };
    }

    /**
     * 获取指定类型的默认编解码器。
     *
     * @param typeRef 类型引用
     * @return 编解码器（未匹配时若为枚举则动态构造，否则默认返回字符串直通）
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static TypeCodec<?> forType(TypeRef typeRef) {
        if (typeRef == null) {
            return null;
        }
        Class<?> raw = typeRef.rawType();
        if (raw == null) {
            return null;
        }
        TypeCodec<?> codec = BUILTINS.get(raw);
        if (codec != null) {
            return codec;
        }
        if (raw.isEnum()) {
            return forEnum((Class<? extends Enum>) raw);
        }
        return null;
    }

    /**
     * 解析时间长度字面量（如 100ms, 3s, 5m, 1h, 10d, PT10S）。
     *
     * @param text 文本字面量
     * @return Duration 实例
     */
    public static Duration parseDuration(String text) {
        if (text == null) {
            throw new IllegalArgumentException("duration string must not be null");
        }
        Duration duration = ConvertUtil.toDuration(text);
        if (duration == null) {
            throw new IllegalArgumentException("Invalid duration literal: " + text);
        }
        return duration;
    }
}
