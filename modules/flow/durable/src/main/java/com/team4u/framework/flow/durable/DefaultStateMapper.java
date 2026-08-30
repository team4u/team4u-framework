package com.team4u.framework.flow.durable;

import java.nio.charset.StandardCharsets;

/**
 * 默认的基础状态编解码器实现。
 * 支持基础原始类型（String, Integer, Long, Boolean, Double, Float, Short, Byte, Character, byte[]）与 null。
 * 领域对象需要自定义 {@link StateMapper} 实现。
 *
 * @author jay.wu
 */
public class DefaultStateMapper implements StateMapper {

    public static final DefaultStateMapper INSTANCE = new DefaultStateMapper();

    @Override
    public StoredValue encode(Object value) throws Exception {
        if (value == null) {
            return new StoredValue("null", new byte[0]);
        }
        if (value instanceof String) {
            return new StoredValue("string", ((String) value).getBytes(StandardCharsets.UTF_8));
        }
        if (value instanceof Integer) {
            return new StoredValue("int", value.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (value instanceof Long) {
            return new StoredValue("long", value.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (value instanceof Boolean) {
            return new StoredValue("bool", value.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (value instanceof Double) {
            return new StoredValue("double", value.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (value instanceof Float) {
            return new StoredValue("float", value.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (value instanceof Short) {
            return new StoredValue("short", value.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (value instanceof Byte) {
            return new StoredValue("byte", value.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (value instanceof Character) {
            return new StoredValue("char", value.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (value instanceof byte[]) {
            return new StoredValue("bytes", (byte[]) value);
        }
        throw new IllegalArgumentException("Cannot encode unsupported type: " + value.getClass().getName() +
                ". Implement custom StateMapper for domain types.");
    }

    @Override
    public Object decode(StoredValue storedValue) throws Exception {
        if (storedValue == null) {
            throw new IllegalArgumentException("storedValue must not be null");
        }
        String type = storedValue.typeId();
        byte[] data = storedValue.data();

        switch (type) {
            case "null":
                return null;
            case "string":
                return new String(data, StandardCharsets.UTF_8);
            case "int":
                return Integer.parseInt(new String(data, StandardCharsets.UTF_8));
            case "long":
                return Long.parseLong(new String(data, StandardCharsets.UTF_8));
            case "bool":
                String boolStr = new String(data, StandardCharsets.UTF_8);
                if ("true".equals(boolStr)) {
                    return Boolean.TRUE;
                } else if ("false".equals(boolStr)) {
                    return Boolean.FALSE;
                } else {
                    throw new IllegalArgumentException("Invalid boolean data in StoredValue: " + boolStr);
                }
            case "double":
                return Double.parseDouble(new String(data, StandardCharsets.UTF_8));
            case "float":
                return Float.parseFloat(new String(data, StandardCharsets.UTF_8));
            case "short":
                return Short.parseShort(new String(data, StandardCharsets.UTF_8));
            case "byte":
                return Byte.parseByte(new String(data, StandardCharsets.UTF_8));
            case "char":
                String charStr = new String(data, StandardCharsets.UTF_8);
                if (charStr.length() != 1) {
                    throw new IllegalArgumentException("Invalid char data in StoredValue (must be exactly 1 char): " + charStr);
                }
                return charStr.charAt(0);
            case "bytes":
                return data;
            default:
                throw new IllegalArgumentException("Unknown or unsupported typeId in StoredValue: " + type +
                        ". Implement custom StateMapper for domain types.");
        }
    }
}
