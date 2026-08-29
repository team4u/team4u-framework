package com.team4u.framework.singleflight.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team4u.framework.serializer.json.JsonUtil;

/**
 * Immutable envelope stored under {@code singleflight.session}.
 * <p>
 * {@code token} is deliberately the first field. The envelope JSON itself is
 * the CAS expected value, so a stale executor can never overwrite the session
 * created by the executor that took over the lock.
 * </p>
 *
 * @author jay.wu
 */
public final class SessionEnvelope {

    public static final String STATE_PENDING = "PENDING";
    public static final String STATE_SUCCESS_CACHEABLE = "SUCCESS_CACHEABLE";
    public static final String STATE_SUCCESS_NOT_CACHEABLE = "SUCCESS_NOT_CACHEABLE";
    public static final String STATE_FAILURE = "FAILURE";

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final ObjectNode json;

    private SessionEnvelope(ObjectNode json) {
        this.json = json;
    }

    public static SessionEnvelope pending(String token, long nowMillis) {
        ObjectNode node = NODES.objectNode();
        node.put("token", token);
        node.put("state", STATE_PENDING);
        node.put("startedAtMillis", nowMillis);
        return new SessionEnvelope(node);
    }

    public static SessionEnvelope success(String token, JsonNode result, boolean cacheable,
                                          long finishedAtMillis) {
        ObjectNode node = NODES.objectNode();
        node.put("token", token);
        node.put("state", cacheable ? STATE_SUCCESS_CACHEABLE : STATE_SUCCESS_NOT_CACHEABLE);
        node.set("result", result);
        node.put("finishedAtMillis", finishedAtMillis);
        return new SessionEnvelope(node);
    }

    public static SessionEnvelope failure(String token, String errorMessage, long finishedAtMillis) {
        ObjectNode node = NODES.objectNode();
        node.put("token", token);
        node.put("state", STATE_FAILURE);
        node.put("error", errorMessage == null ? "loader failed" : errorMessage);
        node.put("finishedAtMillis", finishedAtMillis);
        return new SessionEnvelope(node);
    }

    public static SessionEnvelope of(String json) {
        Object parsed = JsonUtil.parseObj(json);
        if (!(parsed instanceof ObjectNode)) {
            throw new IllegalArgumentException("Session envelope must be a JSON object");
        }
        return new SessionEnvelope((ObjectNode) parsed);
    }

    static SessionEnvelope fromObjectNode(ObjectNode json) {
        return new SessionEnvelope(json);
    }

    ObjectNode mutableJson() {
        return json.deepCopy();
    }

    public String token() {
        JsonNode token = json.get("token");
        return token == null || token.isNull() ? null : token.asText();
    }

    public String state() {
        JsonNode state = json.get("state");
        return state == null ? null : state.asText();
    }

    public boolean hasState(String state) {
        return state != null && state.equals(state());
    }

    public boolean isTerminal() {
        return STATE_SUCCESS_CACHEABLE.equals(state())
                || STATE_SUCCESS_NOT_CACHEABLE.equals(state())
                || STATE_FAILURE.equals(state());
    }

    public JsonNode result() {
        return json.get("result");
    }

    public String errorMessage() {
        JsonNode error = json.get("error");
        return error == null || error.isNull() ? null : error.asText();
    }

    public String toJson() {
        return json.toString();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof SessionEnvelope && json.equals(((SessionEnvelope) obj).json);
    }

    @Override
    public int hashCode() {
        return json.hashCode();
    }
}
