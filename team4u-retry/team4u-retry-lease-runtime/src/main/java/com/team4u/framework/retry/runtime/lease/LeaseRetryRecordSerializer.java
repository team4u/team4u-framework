package com.team4u.framework.retry.runtime.lease;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team4u.framework.retry.api.RecoverySpec;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.common.backoff.Backoff;
import com.team4u.framework.retry.common.backoff.BackoffRegistry;
import com.team4u.framework.retry.config.BackoffConfig;
import com.team4u.framework.retry.managed.model.RetryRequest;
import com.team4u.framework.retry.managed.model.RetryState;
import com.team4u.framework.retry.managed.model.RetryStatus;
import com.team4u.framework.retry.managed.store.record.RetryRecord;
import com.team4u.framework.retry.managed.store.serialize.RetryRecordSerializer;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Collections;
import java.util.Map;

/**
 * Explicit versioned JSON mapping for durable retry records.
 *
 * <p>The format intentionally does not encode Java implementation class names for records or
 * backoff strategies. Only retry exception classes are represented by name, and deserialization
 * accepts them only from the configured allowlist.</p>
 */
public final class LeaseRetryRecordSerializer implements RetryRecordSerializer {

    public static final int SCHEMA_VERSION = 1;

    public static final LeaseRetryRecordSerializer INSTANCE =
            new LeaseRetryRecordSerializer(BackoffRegistry.global());

    private static final String VERSION_FIELD = "version";
    private static final String TASK_ID_FIELD = "taskId";
    private static final String REQUEST_FIELD = "request";
    private static final String STATE_FIELD = "state";
    private static final String RECOVERY_FIELD = "recovery";
    private static final String POLICY_FIELD = "policy";
    private static final String BACKOFF_FIELD = "backoff";
    private static final String TYPE_FIELD = "type";
    private static final String PARAMS_FIELD = "params";

    private final ObjectMapper mapper = new ObjectMapper();
    private final BackoffRegistry backoffRegistry;
    private final Set<Class<? extends Throwable>> throwableAllowlist;

    public LeaseRetryRecordSerializer() {
        this(BackoffRegistry.global());
    }

    public LeaseRetryRecordSerializer(BackoffRegistry backoffRegistry) {
        this(backoffRegistry, Collections.<Class<? extends Throwable>>emptySet());
    }

    public LeaseRetryRecordSerializer(
            BackoffRegistry backoffRegistry, Set<Class<? extends Throwable>> throwableAllowlist) {
        if (backoffRegistry == null) {
            throw new IllegalArgumentException("BackoffRegistry must not be null");
        }
        if (throwableAllowlist == null) {
            throw new IllegalArgumentException("throwableAllowlist must not be null");
        }
        for (Class<?> type : throwableAllowlist) {
            if (type == null || !Throwable.class.isAssignableFrom(type)) {
                throw new IllegalArgumentException(
                        "throwableAllowlist entries must be Throwable subtypes");
            }
        }
        this.backoffRegistry = backoffRegistry;
        Set<Class<? extends Throwable>> copied =
                new LinkedHashSet<Class<? extends Throwable>>();
        copied.addAll(throwableAllowlist);
        this.throwableAllowlist = Collections.unmodifiableSet(copied);
    }

    BackoffRegistry registry() {
        return backoffRegistry;
    }

    @Override
    public String serialize(RetryRecord record) {
        if (record == null || record.getRequest() == null || record.getState() == null) {
            throw new IllegalStateException("RetryRecord, request, and state must not be null");
        }
        RetryRequest request = record.getRequest();
        RetryPolicy policy = request.getPolicy();
        if (policy == null || request.getRecovery() == null) {
            throw new IllegalStateException("RetryRequest policy and recovery must not be null");
        }
        requireDurableTaskIdConsistency(record);
        requireDurableRequest(request);
        validateThrowableClasses(
                "RetryPolicy.retryOnExceptions", policy.getRetryOnExceptions());
        validateThrowableClasses(
                "RetryPolicy.abortOnExceptions", policy.getAbortOnExceptions());
        validateTerminalState(record.getState());
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put(VERSION_FIELD, SCHEMA_VERSION);
            putNullable(root, TASK_ID_FIELD, record.getTaskId());
            root.set(REQUEST_FIELD, request(request, record.getTaskId()));
            root.set(STATE_FIELD, state(record.getState()));
            return mapper.writeValueAsString(root);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize RetryRecord schema v1", ex);
        }
    }

    @Override
    public RetryRecord deserialize(String data) {
        try {
            JsonNode tree = mapper.readTree(data);
            requireObject(tree, "RetryRecord");
            ObjectNode root = (ObjectNode) tree;
            int version = intAt(root, VERSION_FIELD, "RetryRecord.version");
            if (version != SCHEMA_VERSION) {
                throw new IllegalArgumentException(
                        "Unsupported RetryRecord schema version: " + version);
            }
            String taskId = optionalText(root, TASK_ID_FIELD);
            RetryRequest request = request(requiredObject(root, REQUEST_FIELD, "RetryRecord.request"));
            RetryState state = state(requiredObject(root, STATE_FIELD, "RetryRecord.state"));
            if (taskId == null && request.getTaskId() != null) {
                taskId = request.getTaskId();
            }
            if (taskId != null && request.getTaskId() != null
                    && !taskId.equals(request.getTaskId())) {
                throw new IllegalArgumentException(
                        "RetryRecord.taskId must match RetryRequest.taskId when both are present");
            }
            if (request.getCreatedAt() == null) {
                throw new IllegalArgumentException(
                        "Durable RetryRequest.createdAt must not be null");
            }
            if (taskId != null) {
                request.setTaskId(taskId);
            }
            return RetryRecord.builder()
                    .taskId(taskId)
                    .request(request)
                    .state(state)
                    .build();
        } catch (IllegalStateException | IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to deserialize RetryRecord schema v1", ex);
        }
    }

    private ObjectNode request(RetryRequest request, String taskId) {
        ObjectNode node = mapper.createObjectNode();
        putNullable(node, TASK_ID_FIELD, request.getTaskId() == null ? taskId : request.getTaskId());
        putNullable(node, "taskType", request.getTaskType());
        putNullable(node, "idempotencyKey", request.getIdempotencyKey());
        node.set(RECOVERY_FIELD, recovery(request.getRecovery()));
        node.set(POLICY_FIELD, policy(request.getPolicy()));
        putInstant(node, "createdAt", request.getCreatedAt());
        return node;
    }

    private RetryRequest request(ObjectNode node) {
        RecoverySpec recovery = recovery(requiredObject(node, RECOVERY_FIELD, "RetryRequest.recovery"));
        return RetryRequest.builder()
                .taskId(optionalText(node, TASK_ID_FIELD))
                .taskType(requiredText(node, "taskType", "RetryRequest.taskType"))
                .idempotencyKey(optionalText(node, "idempotencyKey"))
                .recovery(recovery)
                .policy(policy(requiredObject(node, POLICY_FIELD, "RetryRequest.policy")))
                .createdAt(optionalInstant(node, "createdAt", "RetryRequest.createdAt"))
                .build();
    }

    private ObjectNode recovery(RecoverySpec recovery) {
        ObjectNode node = mapper.createObjectNode();
        putNullable(node, TASK_ID_FIELD, null);
        node.put("taskType", recovery.getTaskType());
        putNullable(node, "payload", recovery.getPayload());
        node.remove(TASK_ID_FIELD);
        return node;
    }

    private RecoverySpec recovery(ObjectNode node) {
        return new RecoverySpec(
                requiredText(node, "taskType", "RecoverySpec.taskType"),
                optionalText(node, "payload"));
    }

    private ObjectNode policy(RetryPolicy policy) {
        ObjectNode node = mapper.createObjectNode();
        if (policy.getForegroundMaxRetries() == null) {
            node.putNull("foregroundMaxRetries");
        } else {
            node.put("foregroundMaxRetries", policy.getForegroundMaxRetries());
        }
        node.put("maxRetries", policy.getMaxRetries());
        node.set(BACKOFF_FIELD, backoff(policy.getBackoff()));
        strings(node, "retryOn", policy.getRetryOnExceptions());
        strings(node, "abortOn", policy.getAbortOnExceptions());
        putNullable(node, "condition", policy.getCondition());
        return node;
    }

    private RetryPolicy policy(ObjectNode node) {
        return RetryPolicy.builder()
                .maxRetries(maxRetries(node))
                .foregroundMaxRetries(nullableInteger(node, "foregroundMaxRetries"))
                .backoff(backoff(requiredObject(node, BACKOFF_FIELD, "RetryPolicy.backoff")))
                .retryOnExceptions(throwableClasses(node, "retryOn"))
                .abortOnExceptions(throwableClasses(node, "abortOn"))
                .condition(optionalText(node, "condition"))
                .build();
    }

    private ObjectNode backoff(Backoff backoff) {
        BackoffConfig config;
        try {
            config = backoff.toConfig();
        } catch (UnsupportedOperationException ex) {
            throw new IllegalStateException(ex.getMessage(), ex);
        }
        if (config == null || isBlank(config.getType())) {
            throw new IllegalStateException(
                    "Backoff.toConfig() must return a BackoffConfig with a non-blank type: "
                            + backoff.getClass().getName()
                            + "; provide a custom RetryRecordSerializer");
        }

        ObjectNode node = mapper.createObjectNode();
        node.put(TYPE_FIELD, normalizeType(config.getType()));
        ObjectNode params = mapper.createObjectNode();
        Map<String, Object> values = config.getParams() == null
                ? Collections.<String, Object>emptyMap() : config.getParams();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (entry.getValue() == null) {
                throw new IllegalStateException("Backoff param must not be null: " + entry.getKey());
            }
            putValue(params, entry.getKey(), entry.getValue());
        }
        node.set(PARAMS_FIELD, params);
        return node;
    }

    private Backoff backoff(ObjectNode node) {
        String type = requiredText(node, TYPE_FIELD, "Backoff.type");
        Map<String, Object> values =
                params(requiredObject(node, PARAMS_FIELD, "Backoff.params"));
        validateBuiltinBackoffParams(type, values, node.get(PARAMS_FIELD));
        BackoffConfig config = new BackoffConfig();
        config.setType(type);
        config.setParams(values);
        try {
            return backoffRegistry.createBackoff(config);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(
                    "Unknown or invalid backoff type: " + type, ex);
        }
    }

    private static void validateBuiltinBackoffParams(
            String type, Map<String, Object> values, JsonNode rawParams) {
        Map<String, Class<?>> required;
        if ("fixed".equals(type)) {
            required = Collections.<String, Class<?>>singletonMap("delay", Long.class);
        } else if ("increment".equals(type)) {
            required = new LinkedHashMap<String, Class<?>>();
            required.put("initialDelay", Long.class);
            required.put("stepMillis", Long.class);
        } else if ("exponential".equals(type) || "exponentialJitter".equals(type)) {
            required = new LinkedHashMap<String, Class<?>>();
            required.put("initialDelay", Long.class);
            required.put("multiplier", Double.class);
            required.put("maxDelay", Long.class);
        } else {
            return;
        }
        if (values.size() != required.size()) {
            throw new IllegalArgumentException(
                    "Builtin backoff " + type + " requires exactly " + required.keySet()
                            + ": found " + values.keySet());
        }
        for (Map.Entry<String, Class<?>> entry : required.entrySet()) {
            Object value = values.get(entry.getKey());
            if (value == null || !entry.getValue().isInstance(value)) {
                throw new IllegalArgumentException(
                        "Builtin backoff " + type + " parameter " + entry.getKey()
                                + " must be a number");
            }
        }
    }

    private Map<String, Object> params(ObjectNode node) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext();) {
            Map.Entry<String, JsonNode> entry = it.next();
            result.put(entry.getKey(), value(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    private Object value(String name, JsonNode node) {
        if (node.isTextual()) return node.asText();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isInt() || node.isLong()) return node.asLong();
        if (node.isFloatingPointNumber()) return node.asDouble();
        if (node.isArray()) {
            java.util.List<Object> values = new ArrayList<Object>();
            for (JsonNode child : node) {
                values.add(value(name, child));
            }
            return values;
        }
        if (node.isObject()) {
            Map<String, Object> values = new LinkedHashMap<String, Object>();
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext();) {
                Map.Entry<String, JsonNode> entry = it.next();
                values.put(entry.getKey(), value(entry.getKey(), entry.getValue()));
            }
            return values;
        }
        throw new IllegalArgumentException(
                "Unsupported backoff parameter value: " + name);
    }

    private ObjectNode state(RetryState state) {
        validateTerminalState(state);
        ObjectNode node = mapper.createObjectNode();
        node.put("attempts", state.getAttempts());
        putNullable(node, "status", state.getStatus() == null ? null : state.getStatus().name());
        putInstant(node, "nextRunAt", state.getNextRunAt());
        putNullable(node, "lastErrorCode", state.getLastErrorCode());
        putNullable(node, "lastErrorMessage", state.getLastErrorMessage());
        putInstant(node, "succeededAt", state.getSucceededAt());
        putInstant(node, "failedAt", state.getFailedAt());
        putInstant(node, "cancelledAt", state.getCancelledAt());
        putNullable(node, "backendTaskId", state.getBackendTaskId());
        return node;
    }

    private RetryState state(ObjectNode node) {
        RetryState state = RetryState.builder()
                .attempts(intAt(node, "attempts", "RetryState.attempts"))
                .status(RetryStatus.valueOf(requiredText(node, "status", "RetryState.status")))
                .nextRunAt(optionalInstant(node, "nextRunAt", "RetryState.nextRunAt"))
                .lastErrorCode(optionalText(node, "lastErrorCode"))
                .lastErrorMessage(optionalText(node, "lastErrorMessage"))
                .succeededAt(optionalInstant(node, "succeededAt", "RetryState.succeededAt"))
                .failedAt(optionalInstant(node, "failedAt", "RetryState.failedAt"))
                .cancelledAt(optionalInstant(node, "cancelledAt", "RetryState.cancelledAt"))
                .backendTaskId(optionalText(node, "backendTaskId"))
                .build();
        validateTerminalState(state);
        return state;
    }

    private static void strings(
            ObjectNode node, String field, Set<Class<? extends Throwable>> classes) {
        ArrayNode values = node.putArray(field);
        for (Class<? extends Throwable> clazz : classes) {
            values.add(clazz.getName());
        }
    }

    private Set<Class<? extends Throwable>> throwableClasses(
            ObjectNode node, String field) {
        JsonNode values = node.get(field);
        if (values == null || values.isNull()) {
            return new LinkedHashSet<Class<? extends Throwable>>();
        }
        if (!values.isArray()) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        Set<Class<? extends Throwable>> result = new LinkedHashSet<Class<? extends Throwable>>();
        for (JsonNode value : values) {
            if (!value.isTextual()) {
                throw new IllegalArgumentException(field + " class names must be strings");
            }
            String name = value.asText();
            result.add(throwableClass(name));
        }
        return result;
    }

    private boolean isThrowableAllowed(String name, Class<? extends Throwable> type) {
        if (type.getName().startsWith("java.")) {
            return true;
        }
        return throwableAllowlist.contains(type);
    }

    private Class<? extends Throwable> throwableClass(String name) {
        if (!isThrowableAllowedName(name)) {
            throw new IllegalArgumentException(
                    "Retry exception class is not allowlisted: " + name);
        }
        Class<?> clazz;
        try {
            clazz = Class.forName(name, false,
                    LeaseRetryRecordSerializer.class.getClassLoader());
        } catch (ClassNotFoundException ex) {
            throw new IllegalArgumentException(
                    "Unknown retry exception class: " + name, ex);
        }
        if (!Throwable.class.isAssignableFrom(clazz)) {
            throw new IllegalArgumentException(
                    "Retry exception class must be a Throwable subtype: " + name);
        }
        return clazz.asSubclass(Throwable.class);
    }

    private boolean isThrowableAllowedName(String name) {
        if (name.startsWith("java.")) {
            return true;
        }
        for (Class<? extends Throwable> allowed : throwableAllowlist) {
            if (allowed.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static void putValue(ObjectNode node, String field, Object value) {
        if (value instanceof String) {
            node.put(field, (String) value);
        } else if (value instanceof Boolean) {
            node.put(field, (Boolean) value);
        } else if (value instanceof Integer) {
            node.put(field, (Integer) value);
        } else if (value instanceof Long) {
            node.put(field, (Long) value);
        } else if (value instanceof Double) {
            node.put(field, (Double) value);
        } else if (value instanceof Float) {
            node.put(field, (Float) value);
        } else if (value instanceof Number) {
            node.put(field, ((Number) value).doubleValue());
        } else {
            throw new IllegalStateException(
                    "Unsupported backoff param type for " + field + ": " + value.getClass().getName());
        }
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static void putInstant(ObjectNode node, String field, Instant value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value.toString());
        }
    }

    private static Instant optionalInstant(ObjectNode node, String field, String path) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException(path + " must be an ISO-8601 string");
        }
        try {
            return Instant.parse(value.asText());
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(path + " must be an ISO-8601 string", ex);
        }
    }

    private static String optionalText(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return value.asText();
    }

    private static String requiredText(ObjectNode node, String field, String path) {
        String value = optionalText(node, field);
        if (isBlank(value)) {
            throw new IllegalArgumentException(path + " must not be blank");
        }
        return value;
    }

    private static Integer nullableInteger(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return integer(node, field, field);
    }

    private static int intAt(ObjectNode node, String field, String path) {
        return integer(node, field, path);
    }
    private static int integer(ObjectNode node, String field, String path) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException(path + " must not be null");
        }
        if (!value.isInt() || value.asInt() < 0) {
            throw new IllegalArgumentException(path + " must be a non-negative integer");
        }
        return value.asInt();
    }

    private static Integer maxRetries(ObjectNode policy) {
        JsonNode value = policy.get("maxRetries");
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isInt() || value.asInt() < -1) {
            throw new IllegalArgumentException("RetryPolicy.maxRetries must be an integer >= -1");
        }
        return value.asInt();
    }

    private static ObjectNode requiredObject(ObjectNode node, String field, String path) {
        JsonNode value = node.get(field);
        if (!(value instanceof ObjectNode)) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        return (ObjectNode) value;
    }

    private static void requireObject(JsonNode node, String path) {
        if (!(node instanceof ObjectNode)) {
            throw new IllegalArgumentException(path + " must be an object");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String normalizeType(String type) {
        return isBlank(type) ? "fixed" : type.trim();
    }

    private static void requireDurableTaskIdConsistency(RetryRecord record) {
        if (record.getTaskId() != null && record.getRequest().getTaskId() != null
                && !record.getTaskId().equals(record.getRequest().getTaskId())) {
            throw new IllegalStateException(
                    "RetryRecord.taskId must match RetryRequest.taskId when both are present");
        }
    }

    private static void requireDurableRequest(RetryRequest request) {
        if (isBlank(request.getTaskType())) {
            throw new IllegalStateException(
                    "Durable RetryRequest.taskType must not be blank");
        }
        if (isBlank(request.getIdempotencyKey())) {
            throw new IllegalStateException(
                    "Durable RetryRequest.idempotencyKey must not be blank");
        }
        if (request.getCreatedAt() == null) {
            throw new IllegalStateException(
                    "Durable RetryRequest.createdAt must not be null");
        }
    }

    private void validateThrowableClasses(
            String field, Set<Class<? extends Throwable>> classes) {
        for (Class<? extends Throwable> type : classes) {
            if (!isThrowableAllowed(type.getName(), type)) {
                throw new IllegalStateException(
                        field + " entry is not allowlisted: " + type.getName());
            }
        }
    }

    private static void validateTerminalState(RetryState state) {
        if (state.getStatus() == RetryStatus.SUCCEEDED && state.getSucceededAt() == null) {
            throw new IllegalStateException(
                    "SUCCEEDED RetryState.succeededAt must not be null");
        }
        if (state.getStatus() == RetryStatus.FAILED && state.getFailedAt() == null) {
            throw new IllegalStateException(
                    "FAILED RetryState.failedAt must not be null");
        }
        if (state.getStatus() == RetryStatus.CANCELLED
                && state.getCancelledAt() == null) {
            throw new IllegalStateException(
                    "CANCELLED RetryState.cancelledAt must not be null");
        }
    }
}
