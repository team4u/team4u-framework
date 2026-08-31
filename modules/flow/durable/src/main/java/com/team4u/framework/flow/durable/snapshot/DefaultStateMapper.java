package com.team4u.framework.flow.durable.snapshot;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 默认基础标量类型业务数据编解码器（Default State Mapper）。
 *
 * <p>基于原生 JDK 实现，内置支持 String、Integer、Long、Boolean、Double、Float、Short、Byte、Character、byte[] 以及 Instant 类型的确定性编解码。
 * 内部基于无锁不可变表分发，消除长链分支判断。</p>
 *
 * @author jay.wu
 */
public final class DefaultStateMapper implements StateMapper {
    /** 默认单例实例。 */
    public static final DefaultStateMapper INSTANCE = new DefaultStateMapper();
    private static final int VERSION = 1;

    private static final Map<Class<?>, ScalarEncoder> ENCODERS = new HashMap<Class<?>, ScalarEncoder>();
    private static final Map<String, ScalarDecoder> DECODERS = new HashMap<String, ScalarDecoder>();

    private interface ScalarEncoder {
        StoredValue encode(Object value);
    }

    private interface ScalarDecoder {
        Object decode(byte[] payload);
    }

    static {
        // Encoders
        registerEncoder(String.class, value -> text("string", (String) value));
        registerEncoder(Integer.class, value -> text("int", value.toString()));
        registerEncoder(Long.class, value -> text("long", value.toString()));
        registerEncoder(Boolean.class, value -> text("boolean", value.toString()));
        registerEncoder(Double.class, value -> text("double", value.toString()));
        registerEncoder(Float.class, value -> text("float", value.toString()));
        registerEncoder(Short.class, value -> text("short", value.toString()));
        registerEncoder(Byte.class, value -> text("byte", value.toString()));
        registerEncoder(Character.class, value -> text("char", value.toString()));
        registerEncoder(byte[].class, value -> new StoredValue("team4u-default:bytes", VERSION, (byte[]) value));
        registerEncoder(Instant.class, value -> {
            Instant instant = (Instant) value;
            ByteBuffer bytes = ByteBuffer.allocate(12);
            bytes.putLong(instant.getEpochSecond());
            bytes.putInt(instant.getNano());
            return new StoredValue("team4u-default:instant", VERSION, bytes.array());
        });

        // Decoders
        registerDecoder("team4u-default:string", payload -> new String(payload, StandardCharsets.UTF_8));
        registerDecoder("team4u-default:int", payload -> Integer.valueOf(new String(payload, StandardCharsets.UTF_8)));
        registerDecoder("team4u-default:long", payload -> Long.valueOf(new String(payload, StandardCharsets.UTF_8)));
        registerDecoder("team4u-default:boolean", payload -> {
            String str = new String(payload, StandardCharsets.UTF_8);
            if ("true".equals(str)) return Boolean.TRUE;
            if ("false".equals(str)) return Boolean.FALSE;
            throw new IllegalArgumentException("Invalid boolean payload: " + str);
        });
        registerDecoder("team4u-default:double", payload -> Double.valueOf(new String(payload, StandardCharsets.UTF_8)));
        registerDecoder("team4u-default:float", payload -> Float.valueOf(new String(payload, StandardCharsets.UTF_8)));
        registerDecoder("team4u-default:short", payload -> Short.valueOf(new String(payload, StandardCharsets.UTF_8)));
        registerDecoder("team4u-default:byte", payload -> Byte.valueOf(new String(payload, StandardCharsets.UTF_8)));
        registerDecoder("team4u-default:char", payload -> {
            String str = new String(payload, StandardCharsets.UTF_8);
            if (str.length() != 1) {
                throw new IllegalArgumentException("Invalid character payload");
            }
            return Character.valueOf(str.charAt(0));
        });
        registerDecoder("team4u-default:bytes", payload -> payload);
        registerDecoder("team4u-default:instant", payload -> {
            if (payload.length != 12) {
                throw new IllegalArgumentException("Invalid Instant payload");
            }
            ByteBuffer bytes = ByteBuffer.wrap(payload);
            return Instant.ofEpochSecond(bytes.getLong(), bytes.getInt());
        });
    }

    private static void registerEncoder(Class<?> type, ScalarEncoder encoder) {
        ENCODERS.put(type, encoder);
    }

    private static void registerDecoder(String codecId, ScalarDecoder decoder) {
        DECODERS.put(codecId, decoder);
    }

    private DefaultStateMapper() {
    }

    @Override
    public StoredValue encode(Object value) {
        Objects.requireNonNull(value, "value must not be null");
        ScalarEncoder encoder = ENCODERS.get(value.getClass());
        if (encoder == null) {
            throw new IllegalArgumentException("Unsupported state type: " + value.getClass().getName());
        }
        return encoder.encode(value);
    }

    @Override
    public Object decode(StoredValue storedValue) {
        Objects.requireNonNull(storedValue, "storedValue must not be null");
        if (storedValue.codecVersion() != VERSION) {
            throw new IllegalArgumentException("Unsupported default codec version: " + storedValue.codecVersion());
        }
        ScalarDecoder decoder = DECODERS.get(storedValue.codecId());
        if (decoder == null) {
            throw new IllegalArgumentException("Unsupported codec: " + storedValue.codecId());
        }
        return decoder.decode(storedValue.payload());
    }

    private static StoredValue text(String type, String value) {
        return new StoredValue("team4u-default:" + type, VERSION,
                value.getBytes(StandardCharsets.UTF_8));
    }
}
