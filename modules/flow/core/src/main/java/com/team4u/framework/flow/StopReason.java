package com.team4u.framework.flow;

import java.util.Objects;

/**
 * 业务预期停止原因。
 *
 * @author jay.wu
 */
public final class StopReason {

    private final String code;
    private final String message;

    private StopReason(String code, String message) {
        if (code == null || code.trim().isEmpty()) {
            throw new IllegalArgumentException("StopReason code must not be null or blank");
        }
        this.code = code;
        this.message = message != null ? message : "";
    }

    public static StopReason of(String code) {
        return new StopReason(code, "");
    }

    public static StopReason of(String code, String message) {
        return new StopReason(code, message);
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StopReason)) return false;
        StopReason that = (StopReason) o;
        return Objects.equals(code, that.code) && Objects.equals(message, that.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, message);
    }

    @Override
    public String toString() {
        return message.isEmpty() ? code : code + ": " + message;
    }
}
