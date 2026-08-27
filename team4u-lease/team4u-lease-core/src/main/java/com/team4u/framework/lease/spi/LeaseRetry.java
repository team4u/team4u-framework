package com.team4u.framework.lease.spi;

import com.team4u.framework.lease.api.Task;

import java.util.Map;

public final class LeaseRetry {

    private final long delayMillis;
    private final String payload;
    private final String errorMessage;
    private final Map<String, String> attributes;
    private final boolean attributesPresent;

    private LeaseRetry(long delayMillis, String payload, String errorMessage,
                       Map<String, String> attributes, boolean attributesPresent) {
        this.delayMillis = LeaseValues.requireMillis(delayMillis, "delayMillis");
        this.payload = payload;
        this.errorMessage = errorMessage;
        this.attributes = attributes == null ? java.util.Collections.<String, String>emptyMap()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<String, String>(attributes));
        this.attributesPresent = attributesPresent;
    }

    public static LeaseRetry of(long delayMillis, String payload, String errorMessage,
                                Map<String, String> attributes) {
        return new LeaseRetry(delayMillis, payload, errorMessage, attributes, attributes != null);
    }

    public long getDelayMillis() {
        return delayMillis;
    }

    public String getPayload() {
        return payload;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public boolean hasAttributes() {
        return attributesPresent;
    }
}
