package com.team4u.framework.flow.durable;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;

/**
 * 默认基础标量类型业务数据编解码器（Default State Mapper）。
 *
 * <p>基于原生 JDK 实现，内置支持 String、Integer、Long、Boolean、Double、Float、Short、Byte、Character、byte[] 以及 Instant 类型的确定性编解码。
 * 若业务流携带复杂的领域实体对象（DTO/POJO），建议通过 Jackson/Fastjson/Protobuf 实现自定义 {@link StateMapper}。</p>
 *
 * @author jay.wu
 */
public final class DefaultStateMapper implements StateMapper {
    /** 默认单例实例。 */
    public static final DefaultStateMapper INSTANCE = new DefaultStateMapper();
    private static final int VERSION = 1;

    private DefaultStateMapper() {
    }


    @Override
    public StoredValue encode(Object value) {
        Objects.requireNonNull(value, "value must not be null");
        if (value instanceof String) return text("string", (String) value);
        if (value instanceof Integer) return text("int", value.toString());
        if (value instanceof Long) return text("long", value.toString());
        if (value instanceof Boolean) return text("boolean", value.toString());
        if (value instanceof Double) return text("double", value.toString());
        if (value instanceof Float) return text("float", value.toString());
        if (value instanceof Short) return text("short", value.toString());
        if (value instanceof Byte) return text("byte", value.toString());
        if (value instanceof Character) return text("char", value.toString());
        if (value instanceof byte[]) {
            return new StoredValue("team4u-default:bytes", VERSION, (byte[]) value);
        }
        if (value instanceof Instant) {
            Instant instant = (Instant) value;
            ByteBuffer bytes = ByteBuffer.allocate(12);
            bytes.putLong(instant.getEpochSecond());
            bytes.putInt(instant.getNano());
            return new StoredValue("team4u-default:instant", VERSION, bytes.array());
        }
        throw new IllegalArgumentException("Unsupported state type: "
                + value.getClass().getName());
    }

    @Override
    public Object decode(StoredValue storedValue) {
        Objects.requireNonNull(storedValue, "storedValue must not be null");
        if (storedValue.codecVersion() != VERSION) {
            throw new IllegalArgumentException("Unsupported default codec version: "
                    + storedValue.codecVersion());
        }
        String codec = storedValue.codecId();
        byte[] payload = storedValue.payload();
        if ("team4u-default:bytes".equals(codec)) return payload;
        if ("team4u-default:instant".equals(codec)) {
            if (payload.length != 12) {
                throw new IllegalArgumentException("Invalid Instant payload");
            }
            ByteBuffer bytes = ByteBuffer.wrap(payload);
            return Instant.ofEpochSecond(bytes.getLong(), bytes.getInt());
        }
        String value = new String(payload, StandardCharsets.UTF_8);
        if ("team4u-default:string".equals(codec)) return value;
        if ("team4u-default:int".equals(codec)) return Integer.valueOf(value);
        if ("team4u-default:long".equals(codec)) return Long.valueOf(value);
        if ("team4u-default:boolean".equals(codec)) {
            if ("true".equals(value)) return Boolean.TRUE;
            if ("false".equals(value)) return Boolean.FALSE;
            throw new IllegalArgumentException("Invalid boolean payload: " + value);
        }
        if ("team4u-default:double".equals(codec)) return Double.valueOf(value);
        if ("team4u-default:float".equals(codec)) return Float.valueOf(value);
        if ("team4u-default:short".equals(codec)) return Short.valueOf(value);
        if ("team4u-default:byte".equals(codec)) return Byte.valueOf(value);
        if ("team4u-default:char".equals(codec)) {
            if (value.length() != 1) {
                throw new IllegalArgumentException("Invalid character payload");
            }
            return Character.valueOf(value.charAt(0));
        }
        throw new IllegalArgumentException("Unsupported codec: " + codec);
    }

    private static StoredValue text(String type, String value) {
        return new StoredValue("team4u-default:" + type, VERSION,
                value.getBytes(StandardCharsets.UTF_8));
    }
}
