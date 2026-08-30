package com.team4u.framework.lease.api;

import com.team4u.framework.base.util.DurationUtil;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Task {

    private final String type;
    private final String payload;
    private final String deduplicationKey;
    private final long delayMillis;
    private final int priority;
    private final Map<String, String> attributes;

    private Task(String type, String payload, String deduplicationKey, long delayMillis,
                 int priority, Map<String, String> attributes) {
        this.type = requireText(type, "type");
        this.payload = payload;
        this.deduplicationKey = deduplicationKey;
        this.delayMillis = delayMillis;
        this.priority = requirePriority(priority);
        this.attributes = immutableAttributes(attributes);
    }

    public static Task of(String type, String payload) {
        return new Task(type, payload, null, 0L, 0, Collections.<String, String>emptyMap());
    }

    public Task deduplicationKey(String deduplicationKey) {
        if (deduplicationKey != null) {
            requireText(deduplicationKey, "deduplicationKey");
        }
        return new Task(type, payload, deduplicationKey, delayMillis, priority, attributes);
    }

    public Task delay(java.time.Duration delay) {
        if (delay == null) {
            throw new IllegalArgumentException("delay must not be null");
        }
        return new Task(type, payload, deduplicationKey,
                DurationUtil.requireExactMillis(delay, "delay"), priority, attributes);
    }

    public Task priority(int priority) {
        return new Task(type, payload, deduplicationKey, delayMillis, requirePriority(priority),
                attributes);
    }

    public Task attribute(String key, String value) {
        requireText(key, "attribute key");
        if (value == null) {
            throw new IllegalArgumentException("attribute value must not be null");
        }
        Map<String, String> next = new LinkedHashMap<String, String>(attributes);
        next.put(key, value);
        return new Task(type, payload, deduplicationKey, delayMillis, priority, next);
    }

    public Task attributes(Map<String, String> attributes) {
        return new Task(type, payload, deduplicationKey, delayMillis, priority, attributes);
    }

    public String getType() {
        return type;
    }

    public String getPayload() {
        return payload;
    }

    public String getDeduplicationKey() {
        return deduplicationKey;
    }

    public java.time.Duration getDelay() {
        return java.time.Duration.ofMillis(delayMillis);
    }

    public int getPriority() {
        return priority;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    static int requireNonNegative(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("attemptCount must not be negative");
        }
        return value;
    }

    static int requirePriority(int priority) {
        if (priority < 0) {
            throw new IllegalArgumentException("priority must not be negative");
        }
        return priority;
    }

    static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static Map<String, String> immutableAttributes(Map<String, String> values) {
        if (values == null) {
            throw new IllegalArgumentException("attributes must not be null");
        }
        Map<String, String> copied = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            requireText(entry.getKey(), "attribute key");
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("attribute value must not be null");
            }
            copied.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(copied);
    }
}
