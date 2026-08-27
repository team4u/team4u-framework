package com.team4u.framework.lease.spi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

final class LeaseValues {

    private LeaseValues() {
    }

    static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static long requireMillis(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must not be negative");
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
