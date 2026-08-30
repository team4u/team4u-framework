package com.team4u.framework.retry.runtime.lease;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team4u.framework.retry.api.RecoverySpec;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.common.backoff.Backoff;
import com.team4u.framework.retry.common.backoff.BackoffRegistry;
import com.team4u.framework.retry.common.backoff.Backoffs;
import com.team4u.framework.retry.managed.model.RetryRequest;
import com.team4u.framework.retry.managed.model.RetryState;
import com.team4u.framework.retry.managed.model.RetryStatus;
import com.team4u.framework.retry.managed.store.record.RetryRecord;
import org.junit.Assert;
import org.junit.Test;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class LeaseRetryRecordSerializerTest {

    private final LeaseRetryRecordSerializer serializer = new LeaseRetryRecordSerializer();

    @Test
    public void testRoundTripsFixedIncrementAndExponentialBackoffs() {
        assertBackoff(Backoffs.fixed(37L), 37L, 37L);
        assertBackoff(Backoffs.increment(20L, 11L), 20L, 31L);
        assertBackoff(Backoffs.exponential(10L, 2.5D, 1000L), 10L, 25L);
    }

    @Test
    public void testJitterRoundTripAndRange() throws Exception {
        RetryPolicy policy = policy(Backoffs.exponentialJitter(20L, 3.0D, 500L));
        String encoded = serializer.serialize(record(policy));
        RetryRecord restored = serializer.deserialize(encoded);
        Backoff backoff = restored.getRequest().getPolicy().getBackoff();

        Assert.assertEquals(ExponentialJitterAccess.type(backoff), "exponentialJitter");
        Assert.assertEquals(20L, backoff.calculateMillis(1));
        for (int attempt = 2; attempt <= 30; attempt++) {
            long value = backoff.calculateMillis(attempt);
            Assert.assertTrue("jitter below lower bound: " + value, value >= 20L);
            Assert.assertTrue("jitter above upper bound: " + value, value <= 500L);
        }

        JsonNode config = new ObjectMapper().readTree(encoded)
                .get("request").get("policy").get("backoff");
        Assert.assertEquals("exponentialJitter", config.get("type").asText());
        Assert.assertEquals(20L, config.get("params").get("initialDelay").asLong());
        Assert.assertEquals(3.0D, config.get("params").get("multiplier").asDouble(), 0.0D);
        Assert.assertEquals(500L, config.get("params").get("maxDelay").asLong());
    }

    @Test
    public void testRoundTripsPolicyExceptionsAndCondition() {
        RetryPolicy policy = RetryPolicy.builder()
                .maxRetries(4)
                .foregroundMaxRetries(1)
                .backoff(Backoffs.increment(4L, 6L))
                .retryOn(IllegalStateException.class)
                .retryOn(IllegalArgumentException.class)
                .abortOn(InterruptedException.class)
                .condition("message != 'stop'")
                .build();
        RetryPolicy restored = roundTrip(record(policy)).getRequest().getPolicy();

        Assert.assertEquals(4, restored.getMaxRetries());
        Assert.assertEquals(Integer.valueOf(1), restored.getForegroundMaxRetries());
        Assert.assertEquals(4L, restored.getDelayMillis(1));
        Assert.assertEquals(10L, restored.getDelayMillis(2));
        Assert.assertTrue(restored.getRetryOnExceptions().contains(IllegalStateException.class));
        Assert.assertTrue(restored.getRetryOnExceptions().contains(IllegalArgumentException.class));
        Assert.assertTrue(restored.getAbortOnExceptions().contains(InterruptedException.class));
        Assert.assertEquals("message != 'stop'", restored.getCondition());
    }

    @Test
    public void testRoundTripsRequestAndEveryStateTimestamp() {
        Instant createdAt = Instant.parse("2024-01-02T03:04:05Z");
        Instant nextRunAt = Instant.parse("2024-01-02T03:05:05Z");
        Instant succeededAt = Instant.parse("2024-01-02T03:06:05Z");
        Instant failedAt = Instant.parse("2024-01-02T03:07:05Z");
        Instant cancelledAt = Instant.parse("2024-01-02T03:08:05Z");
        RetryRecord record = fullRecord(createdAt, nextRunAt, succeededAt, failedAt, cancelledAt);
        RetryRecord restored = new LeaseRetryRecordSerializer()
                .deserialize(serializer.serialize(record));

        Assert.assertEquals("task-1", restored.getTaskId());
        Assert.assertEquals("payment", restored.getRequest().getTaskType());
        Assert.assertEquals("id-1", restored.getRequest().getIdempotencyKey());
        Assert.assertEquals("payload-1", restored.getRequest().getRecovery().getPayload());
        Assert.assertEquals(createdAt, restored.getRequest().getCreatedAt());
        Assert.assertEquals(7, restored.getState().getAttempts());
        Assert.assertEquals(RetryStatus.SUCCEEDED, restored.getState().getStatus());
        Assert.assertEquals(nextRunAt, restored.getState().getNextRunAt());
        Assert.assertEquals("boom-code", restored.getState().getLastErrorCode());
        Assert.assertEquals("boom-message", restored.getState().getLastErrorMessage());
        Assert.assertEquals(succeededAt, restored.getState().getSucceededAt());
        Assert.assertEquals(failedAt, restored.getState().getFailedAt());
        Assert.assertEquals(cancelledAt, restored.getState().getCancelledAt());
        Assert.assertEquals("backend-1", restored.getState().getBackendTaskId());
    }

    @Test
    public void testPayloadCanBeReadByNewSerializerInstance() {
        RetryRecord record = fullRecord(
                Instant.now(), Instant.now(), Instant.now(), Instant.now(), Instant.now());
        String encoded = new LeaseRetryRecordSerializer().serialize(record);
        Assert.assertEquals(record.getRequest().getCreatedAt(),
                new LeaseRetryRecordSerializer().deserialize(encoded).getRequest().getCreatedAt());
    }

    @Test(expected = IllegalStateException.class)
    public void testCustomBackoffWithoutConfigFailsFastWithGuidance() {
        serializer.serialize(record(policy(new UnsupportedBackoff())));
    }

    @Test
    public void testCustomRegisteredBackoffRoundTripsThroughConfig() {
        RecordingFactory.count.set(0);
        BackoffRegistry registry = new BackoffRegistry();
        registry.register(new RecordingFactory());
        LeaseRetryRecordSerializer custom = new LeaseRetryRecordSerializer(registry);
        Backoff restored = custom.deserialize(
                        custom.serialize(record(policy(new RecordingBackoff()))))
                .getRequest().getPolicy().getBackoff();

        Assert.assertTrue(restored instanceof RecordingBackoff);
        Assert.assertEquals(19L, restored.calculateMillis(1));
        Assert.assertEquals(1, RecordingFactory.count.get());
    }

    @Test
    public void testRejectsMaliciousAndUnknownClasses() {
        assertPayloadRejected(retryOn("java.lang.Runtime"));
        assertPayloadRejected(retryOn(String.class.getName()));
        assertPayloadRejected(retryOn("no.such.NotFound"));
    }

    @Test
    public void testRejectsUnknownSchemaVersionAndBackoffType() {
        assertPayloadRejected("{\"version\":0}");
        assertPayloadRejected("{\"version\":2}");
        assertPayloadRejected("{\"version\":1}");
        assertPayloadRejected(retryBackoff("not-a-real-type"));
    }

    private static RetryRecord fullRecord(
            Instant createdAt,
            Instant nextRunAt,
            Instant succeededAt,
            Instant failedAt,
            Instant cancelledAt) {
        return RetryRecord.builder()
                .taskId("task-1")
                .request(RetryRequest.builder()
                        .taskId("task-1")
                        .taskType("payment")
                        .idempotencyKey("id-1")
                        .recovery(RecoverySpec.of("payment", "payload-1"))
                        .policy(policy(Backoffs.fixed(12L)))
                        .createdAt(createdAt)
                        .build())
                .state(RetryState.builder()
                        .attempts(7)
                        .status(RetryStatus.SUCCEEDED)
                        .nextRunAt(nextRunAt)
                        .lastErrorCode("boom-code")
                        .lastErrorMessage("boom-message")
                        .succeededAt(succeededAt)
                        .failedAt(failedAt)
                        .cancelledAt(cancelledAt)
                        .backendTaskId("backend-1")
                        .build())
                .build();
    }

    @Test
    public void testDefaultSerializerRejectsCustomThrowableAndExplicitRegistryRoundTripsIt() {
        RetryPolicy customPolicy = RetryPolicy.builder()
                .maxRetries(2)
                .foregroundMaxRetries(0)
                .backoff(Backoffs.fixed(12L))
                .retryOn(CustomRetryThrowable.class)
                .build();
        try {
            serializer.serialize(record(customPolicy));
            Assert.fail("Expected custom Throwable serialization rejection");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("not allowlisted"));
        }
        assertPayloadRejected(retryOn(CustomRetryThrowable.class.getName()));

        Set<Class<? extends Throwable>> allowed =
                new LinkedHashSet<Class<? extends Throwable>>();
        allowed.add(CustomRetryThrowable.class);
        LeaseRetryRecordSerializer explicit = new LeaseRetryRecordSerializer(
                BackoffRegistry.global(), allowed);
        RetryRecord restored = explicit.deserialize(
                explicit.serialize(record(customPolicy)));
        Assert.assertTrue(restored.getRequest().getPolicy().getRetryOnExceptions()
                .contains(CustomRetryThrowable.class));
    }

    @Test
    public void testBuiltinBackoffParametersAreStrict() {
        assertBackoffRejected(backoffPayload("fixed", params("delay", 1L, "extra", 2L)));
        assertBackoffRejected(backoffPayload("increment", params("initialDelay", 1L)));
        assertBackoffRejected(backoffPayload("exponential", params(
                "initialDelay", 1L, "multiplier", 2.0D)));
        assertBackoffRejected(backoffPayload("exponentialJitter", params(
                "initialDelay", 1L, "multiplier", "not-a-number", "maxDelay", 3L)));
    }

    @Test
    public void testV1ConsistencyValidation() {
        assertPayloadRejected(withRootTaskId("task-root", "task-request"));
        assertPayloadRejected(missingCreatedAt());
        assertPayloadRejected(terminalState("SUCCEEDED", true, false, false));
        assertPayloadRejected(terminalState("FAILED", false, true, false));
        assertPayloadRejected(terminalState("CANCELLED", false, false, true));
    }

    private static void assertBackoff(Backoff backoff, long first, long second) {
        RetryPolicy restored = roundTrip(record(policy(backoff))).getRequest().getPolicy();
        Assert.assertEquals(backoff.getClass(), restored.getBackoff().getClass());
        Assert.assertEquals(first, restored.getBackoff().calculateMillis(1));
        Assert.assertEquals(second, restored.getBackoff().calculateMillis(2));
    }

    private static RetryRecord roundTrip(RetryRecord record) {
        LeaseRetryRecordSerializer serializer = new LeaseRetryRecordSerializer();
        return serializer.deserialize(serializer.serialize(record));
    }

    private static RetryRecord record(RetryPolicy policy) {
        return RetryLeaseTestSupport.retryRecord("payment", "serializer-test", policy);
    }

    private static RetryPolicy policy(Backoff backoff) {
        return RetryPolicy.builder()
                .maxRetries(2)
                .foregroundMaxRetries(0)
                .backoff(backoff)
                .build();
    }

    private static String retryOn(String className) {
        return "{\"version\":1,\"request\":{\"taskType\":\"x\",\"recovery\":{\"taskType\":\"x\"},"
                + "\"policy\":{\"backoff\":{\"type\":\"fixed\",\"params\":{}},"
                + "\"retryOn\":[\"" + className + "\"]}},\"state\":{\"attempts\":0,\"status\":\"ACCEPTED\"}}";
    }

    private static String retryBackoff(String type) {
        return "{\"version\":1,\"request\":{\"taskType\":\"x\",\"recovery\":{\"taskType\":\"x\"},"
                + "\"policy\":{\"backoff\":{\"type\":\"" + type + "\",\"params\":{}}}},"
                + "\"state\":{\"attempts\":0,\"status\":\"ACCEPTED\"}}";
    }

    private static String backoffPayload(String type, String paramsJson) {
        return "{\"version\":1,\"request\":{\"taskType\":\"x\","
                + "\"recovery\":{\"taskType\":\"x\"},\"createdAt\":\"2024-01-01T00:00:00Z\","
                + "\"policy\":{\"backoff\":{\"type\":\"" + type + "\",\"params\":"
                + paramsJson + "}}},\"state\":{\"attempts\":0,\"status\":\"ACCEPTED\"}}";
    }

    private static String params(Object... values) {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < values.length; i += 2) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(values[i]).append("\":");
            Object value = values[i + 1];
            if (value instanceof Number) {
                json.append(value);
            } else {
                json.append('"').append(value).append('"');
            }
        }
        return json.append('}').toString();
    }

    private static void assertBackoffRejected(String payload) {
        try {
            new LeaseRetryRecordSerializer().deserialize(payload);
            Assert.fail("Expected malformed backoff rejection");
        } catch (IllegalArgumentException | IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("backoff"));
        }
    }

    private static String withRootTaskId(String rootTaskId, String requestTaskId) {
        return "{\"version\":1,\"taskId\":\"" + rootTaskId + "\","
                + "\"request\":{\"taskId\":\"" + requestTaskId + "\",\"taskType\":\"x\","
                + "\"recovery\":{\"taskType\":\"x\"},\"createdAt\":\"2024-01-01T00:00:00Z\","
                + "\"policy\":{\"backoff\":{\"type\":\"fixed\",\"params\":{\"delay\":1}}}},"
                + "\"state\":{\"attempts\":0,\"status\":\"ACCEPTED\"}}";
    }

    private static String missingCreatedAt() {
        return "{\"version\":1,\"request\":{\"taskType\":\"x\","
                + "\"recovery\":{\"taskType\":\"x\"},"
                + "\"policy\":{\"backoff\":{\"type\":\"fixed\",\"params\":{\"delay\":1}}}},"
                + "\"state\":{\"attempts\":0,\"status\":\"ACCEPTED\"}}";
    }

    private static String terminalState(
            String status, boolean missingSucceededAt, boolean missingFailedAt,
            boolean missingCancelledAt) {
        return "{\"version\":1,\"request\":{\"taskType\":\"x\","
                + "\"recovery\":{\"taskType\":\"x\"},\"createdAt\":\"2024-01-01T00:00:00Z\","
                + "\"policy\":{\"backoff\":{\"type\":\"fixed\",\"params\":{\"delay\":1}}}},"
                + "\"state\":{\"attempts\":0,\"status\":\"" + status + "\","
                + (missingSucceededAt ? "" : "\"succeededAt\":\"2024-01-01T00:00:01Z\",")
                + (missingFailedAt ? "" : "\"failedAt\":\"2024-01-01T00:00:01Z\",")
                + (missingCancelledAt ? "" : "\"cancelledAt\":\"2024-01-01T00:00:01Z\",")
                + "\"backendTaskId\":null}}";
    }

    private static void assertPayloadRejected(String payload) {
        try {
            new LeaseRetryRecordSerializer().deserialize(payload);
            Assert.fail("Expected malformed payload rejection");
        } catch (IllegalArgumentException | IllegalStateException expected) {
            Assert.assertNotNull(expected.getMessage());
        }
    }

    public static final class CustomRetryThrowable extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    public static final class RecordingBackoff implements Backoff {
        @Override
        public long calculateMillis(int attempt) {
            Backoff.validateAttempt(attempt);
            return 19L;
        }

        @Override
        public com.team4u.framework.retry.config.BackoffConfig toConfig() {
            com.team4u.framework.retry.config.BackoffConfig config =
                    new com.team4u.framework.retry.config.BackoffConfig();
            config.setType("recording");
            config.setParams(java.util.Collections.<String, Object>singletonMap("delay", 19L));
            return config;
        }
    }

    public static final class RecordingFactory
            implements com.team4u.framework.retry.common.backoff.BackoffFactory {
        static final AtomicInteger count = new AtomicInteger();

        @Override
        public String key() {
            return "recording";
        }

        @Override
        public Backoff create(com.team4u.framework.retry.config.BackoffConfig config) {
            count.incrementAndGet();
            return new RecordingBackoff();
        }
    }

    private static final class UnsupportedBackoff implements Backoff {
        @Override
        public long calculateMillis(int attempt) {
            Backoff.validateAttempt(attempt);
            return 1L;
        }
    }

    private static final class ExponentialJitterAccess {
        static String type(Backoff backoff) {
            return backoff.toConfig().getType();
        }
    }
}
