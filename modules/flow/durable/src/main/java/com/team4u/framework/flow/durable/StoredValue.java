package com.team4u.framework.flow.durable;

import java.util.Arrays;
import java.util.Objects;

/**
 * An encoded application value. The runtime treats the payload as opaque data.
 */
public final class StoredValue {
    private final String codecId;
    private final int codecVersion;
    private final byte[] payload;

    public StoredValue(String codecId, int codecVersion, byte[] payload) {
        this.codecId = text(codecId, "codecId");
        if (codecVersion < 1) {
            throw new IllegalArgumentException("codecVersion must be positive");
        }
        this.codecVersion = codecVersion;
        this.payload = Objects.requireNonNull(payload, "payload must not be null").clone();
    }

    public String codecId() {
        return codecId;
    }

    public int codecVersion() {
        return codecVersion;
    }

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
