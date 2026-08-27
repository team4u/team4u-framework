package com.team4u.framework.lease.api;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TaskResult {

    private final Decision decision;
    private final Long retryDelayMillis;
    private final String payload;
    private final String errorMessage;
    private final Map<String, String> attributes;
    private final boolean attributesPresent;

    private TaskResult(Decision decision, Long retryDelayMillis, String payload, String errorMessage,
                       Map<String, String> attributes, boolean attributesPresent) {
        this.decision = decision;
        this.retryDelayMillis = retryDelayMillis;
        this.payload = payload;
        this.errorMessage = errorMessage;
        this.attributes = attributes;
        this.attributesPresent = attributesPresent;
        validate();
    }

    public static TaskResult success() {
        return new TaskResult(Decision.SUCCESS, null, null, null, empty(), false);
    }

    public static TaskResult success(String payload, Map<String, String> attributes) {
        return new TaskResult(Decision.SUCCESS, null, payload, null,
                requiredAttributes(attributes), true);
    }

    public static TaskResult failure() {
        return new TaskResult(Decision.FAILURE, null, null, null, empty(), false);
    }

    public static TaskResult failure(String errorMessage) {
        return new TaskResult(Decision.FAILURE, null, null, errorMessage, empty(), false);
    }

    public static TaskResult failure(String errorMessage, String payload,
                                     Map<String, String> attributes) {
        return new TaskResult(Decision.FAILURE, null, payload, errorMessage,
                requiredAttributes(attributes), true);
    }

    public static TaskResult cancel() {
        return new TaskResult(Decision.CANCEL, null, null, null, empty(), false);
    }

    public static TaskResult cancel(String errorMessage, String payload,
                                    Map<String, String> attributes) {
        return new TaskResult(Decision.CANCEL, null, payload, errorMessage,
                requiredAttributes(attributes), true);
    }

    public static TaskResult retryAfter(Duration delay) {
        return retryResult(delay, null, null, empty(), false);
    }

    public static TaskResult retryAfter(Duration delay, String errorMessage, String payload,
                                        Map<String, String> attributes) {
        return retryResult(delay, errorMessage, payload, requiredAttributes(attributes), true);
    }

    private static TaskResult retryResult(Duration delay, String errorMessage, String payload,
                                          Map<String, String> attributes,
                                          boolean attributesPresent) {
        return new TaskResult(Decision.RETRY,
                Long.valueOf(Durations.requireExactMillis(delay, "retryDelay")),
                payload, errorMessage, attributes, attributesPresent);
    }

    public TaskResult withPayload(String payload) {
        return new TaskResult(decision, retryDelayMillis, payload, errorMessage, attributes,
                attributesPresent);
    }

    public TaskResult withErrorMessage(String errorMessage) {
        return new TaskResult(decision, retryDelayMillis, payload, errorMessage, attributes,
                attributesPresent);
    }

    public TaskResult withAttributes(Map<String, String> attributes) {
        return new TaskResult(decision, retryDelayMillis, payload, errorMessage,
                requiredAttributes(attributes), true);
    }

    public boolean isSuccess() {
        return decision == Decision.SUCCESS;
    }

    public boolean isFailure() {
        return decision == Decision.FAILURE;
    }

    public boolean isCancel() {
        return decision == Decision.CANCEL;
    }

    public boolean isRetry() {
        return decision == Decision.RETRY;
    }

    public Duration getRetryDelay() {
        return retryDelayMillis == null ? null : Duration.ofMillis(retryDelayMillis.longValue());
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

    private void validate() {
        if (decision == Decision.RETRY) {
            if (retryDelayMillis == null || retryDelayMillis.longValue() < 0L) {
                throw new IllegalArgumentException("retryDelay must not be negative");
            }
        } else if (retryDelayMillis != null) {
            throw new IllegalArgumentException("retryDelay is only valid for retry");
        }

        if (decision == Decision.SUCCESS && errorMessage != null) {
            throw new IllegalArgumentException("errorMessage is not valid for success");
        }
    }

    private static Map<String, String> empty() {
        return Collections.emptyMap();
    }

    private static Map<String, String> requiredAttributes(Map<String, String> attributes) {
        if (attributes == null) {
            throw new IllegalArgumentException("attributes must not be null");
        }
        Map<String, String> copied = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            Task.requireText(entry.getKey(), "attribute key");
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("attribute value must not be null");
            }
            copied.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(copied);
    }

    private enum Decision {
        SUCCESS,
        FAILURE,
        CANCEL,
        RETRY
    }
}
