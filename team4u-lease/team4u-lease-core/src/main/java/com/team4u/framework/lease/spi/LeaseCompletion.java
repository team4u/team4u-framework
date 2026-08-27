package com.team4u.framework.lease.spi;

import com.team4u.framework.lease.api.TaskStatus;

import java.util.Map;

public final class LeaseCompletion {

    private final TaskStatus status;
    private final String payload;
    private final String errorMessage;
    private final Map<String, String> attributes;
    private final boolean attributesPresent;

    private LeaseCompletion(TaskStatus status, String payload, String errorMessage,
                            Map<String, String> attributes, boolean attributesPresent) {
        if (status != TaskStatus.SUCCEEDED && status != TaskStatus.FAILED
                && status != TaskStatus.CANCELLED) {
            throw new IllegalArgumentException("completion status must be terminal");
        }
        this.status = status;
        this.payload = payload;
        this.errorMessage = errorMessage;
        this.attributes = attributes == null ? java.util.Collections.<String, String>emptyMap()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<String, String>(attributes));
        this.attributesPresent = attributesPresent;
    }

    public static LeaseCompletion succeeded(String payload, Map<String, String> attributes) {
        return new LeaseCompletion(TaskStatus.SUCCEEDED, payload, null, attributes, attributes != null);
    }

    public static LeaseCompletion failed(String errorMessage, String payload, Map<String, String> attributes) {
        return new LeaseCompletion(TaskStatus.FAILED, payload, errorMessage, attributes,
                attributes != null);
    }

    public static LeaseCompletion cancelled(String errorMessage, String payload,
                                            Map<String, String> attributes) {
        return new LeaseCompletion(TaskStatus.CANCELLED, payload, errorMessage, attributes,
                attributes != null);
    }

    public TaskStatus getStatus() {
        return status;
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
