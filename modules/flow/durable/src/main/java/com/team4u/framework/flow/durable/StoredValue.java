package com.team4u.framework.flow.durable;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.Arrays;
import java.util.Objects;

/**
 * 业务载荷持久化存储值对象（Stored Encoded Value）。
 *
 * <p>表示经过 {@link StateMapper} 编码后的不可变应用数据二进制块，Durable 运行时将其作为不透明载荷（Opaque Data）安全存入快照插槽。</p>
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
public final class StoredValue {
    /** 编码器标识。 */
    private final String codecId;
    /** 编码器版本号。 */
    private final int codecVersion;
    /** 业务载荷字节数组。 */
    private final byte[] payload;

    /**
     * 构造存储值对象。
     *
     * @param codecId      编码器 ID，不能为空
     * @param codecVersion 编码器版本号，必须大于 0
     * @param payload      载荷字节数组，不能为 null
     * @throws NullPointerException     当入参为 null 时抛出
     * @throws IllegalArgumentException 当参数格式非法时抛出
     */
    public StoredValue(String codecId, int codecVersion, byte[] payload) {
        this.codecId = text(codecId, "codecId");
        if (codecVersion < 1) {
            throw new IllegalArgumentException("codecVersion must be positive");
        }
        this.codecVersion = codecVersion;
        this.payload = Objects.requireNonNull(payload, "payload must not be null").clone();
    }

    /**
     * 获取载荷字节数组的副本（保证不可变）。
     *
     * @return 字节数组
     */
    public byte[] payload() {
        return payload.clone();
    }


    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof StoredValue)) return false;
        StoredValue that = (StoredValue) other;
        return codecVersion == that.codecVersion
                && codecId.equals(that.codecId)
                && Arrays.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * codecId.hashCode() + codecVersion) + Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "StoredValue[codecId=" + codecId + ", codecVersion=" + codecVersion
                + ", payloadBytes=" + payload.length + "]";
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
