package com.team4u.framework.flow.durable;

import com.team4u.framework.base.util.Assert;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;

/**
 * 默认的基础状态编解码器实现。
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
        if (value instanceof byte[]) {
            return new StoredValue("bytes", (byte[]) value);
        }
        if (value instanceof Serializable) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
                oos.writeObject(value);
            }
            return new StoredValue("java-serializable", baos.toByteArray());
        }
        throw new IllegalArgumentException("Cannot encode unsupported type: " + value.getClass().getName() +
                ". Implement custom StateMapper or make object Serializable.");
    }

    @Override
    public Object decode(StoredValue storedValue) throws Exception {
        Assert.notNull(storedValue, "storedValue must not be null");
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
                return Boolean.parseBoolean(new String(data, StandardCharsets.UTF_8));
            case "double":
                return Double.parseDouble(new String(data, StandardCharsets.UTF_8));
            case "bytes":
                return data;
            case "java-serializable":
                try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(data))) {
                    return ois.readObject();
                }
            default:
                throw new IllegalArgumentException("Unknown typeId in StoredValue: " + type);
        }
    }
}
