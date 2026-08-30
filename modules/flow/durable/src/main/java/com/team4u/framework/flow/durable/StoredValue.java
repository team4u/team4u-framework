package com.team4u.framework.flow.durable;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * 编码后的持久化值包装对象。
 *
 * @author jay.wu
 */
public final class StoredValue {

    private final String typeId;
    private final byte[] data;

    public StoredValue(String typeId, byte[] data) {
        this.typeId = Objects.requireNonNull(typeId, "typeId must not be null");
        this.data = data != null ? data : new byte[0];
    }

    public static StoredValue of(String typeId, byte[] data) {
        return new StoredValue(typeId, data);
    }

    public static StoredValue ofString(String str) {
        return new StoredValue("string", str != null ? str.getBytes(StandardCharsets.UTF_8) : new byte[0]);
    }

    public String typeId() {
        return typeId;
    }

    public byte[] data() {
        return data;
    }

    public String asString() {
        return new String(data, StandardCharsets.UTF_8);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StoredValue)) return false;
        StoredValue that = (StoredValue) o;
        return Objects.equals(typeId, that.typeId) && Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(typeId) + Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "StoredValue{" + typeId + ", " + data.length + " bytes}";
    }
}
