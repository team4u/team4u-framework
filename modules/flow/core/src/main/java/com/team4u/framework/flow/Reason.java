package com.team4u.framework.flow;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 业务拒绝或弃权/跳过的稳定诊断信息。code 为稳定业务码，message 可读说明，details 键值补充。
 */
public final class Reason {
    private final String code;
    private final String message;
    private final Map<String, String> details;

    public Reason(String code, String message, Map<String, String> details) {
        this.code = text(code, "code");
        this.message = text(message, "message");
        Objects.requireNonNull(details, "details must not be null");
        this.details = Collections.unmodifiableMap(new LinkedHashMap<String, String>(details));
    }

    public static Reason of(String code, String message) {
        return new Reason(code, message, Collections.emptyMap());
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }

    public Map<String, String> details() {
        return details;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Reason reason = (Reason) o;
        return code.equals(reason.code) && message.equals(reason.message) && details.equals(reason.details);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, message, details);
    }

    @Override
    public String toString() {
        return "Reason[code=" + code + ", message=" + message + ", details=" + details + "]";
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.trim().isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
