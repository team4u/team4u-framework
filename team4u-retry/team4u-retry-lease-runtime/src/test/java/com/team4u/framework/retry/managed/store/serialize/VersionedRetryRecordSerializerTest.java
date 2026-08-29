package com.team4u.framework.retry.managed.store.serialize;

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

/**
 * {@link VersionedRetryRecordSerializer} 的单元测试。
 * <p>
 * 验证自 lease-runtime 下沉后的版本化序列化核心语义：
 * round-trip、退避按 type+params 重建、异常 allowlist、版本与结构校验。
 */
public class VersionedRetryRecordSerializerTest {

    private final VersionedRetryRecordSerializer serializer =
            new VersionedRetryRecordSerializer();

    @Test
    public void testRoundTripsFullRecordWithEveryStateTimestamp() {
        Instant createdAt = Instant.parse("2024-01-02T03:04:05Z");
        Instant nextRunAt = Instant.parse("2024-01-02T03:05:05Z");
        Instant succeededAt = Instant.parse("2024-01-02T03:06:05Z");
        Instant failedAt = Instant.parse("2024-01-02T03:07:05Z");
        Instant cancelledAt = Instant.parse("2024-01-02T03:08:05Z");

        RetryRecord record = RetryRecord.builder()
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

        RetryRecord restored = serializer.deserialize(serializer.serialize(record));

        Assert.assertEquals("task-1", restored.getTaskId());
        Assert.assertEquals("payment", restored.getRequest().getTaskType());
        Assert.assertEquals("id-1", restored.getRequest().getIdempotencyKey());
        Assert.assertEquals("payload-1", restored.getRequest().getRecovery().getPayload());
        Assert.assertEquals(createdAt, restored.getRequest().getCreatedAt());
        Assert.assertEquals(7, restored.getState().getAttempts());
        Assert.assertEquals(RetryStatus.SUCCEEDED, restored.getState().getStatus());
        Assert.assertEquals(nextRunAt, restored.getState().getNextRunAt());
        Assert.assertEquals(succeededAt, restored.getState().getSucceededAt());
        Assert.assertEquals(failedAt, restored.getState().getFailedAt());
        Assert.assertEquals(cancelledAt, restored.getState().getCancelledAt());
        Assert.assertEquals("backend-1", restored.getState().getBackendTaskId());
    }

    @Test
    public void testRoundTripsBuiltinBackoffsByTypeAndParams() {
        assertBackoff(Backoffs.fixed(37L), 37L, 37L);
        assertBackoff(Backoffs.increment(20L, 11L), 20L, 31L);
        assertBackoff(Backoffs.exponential(10L, 2.5D, 1000L), 10L, 25L);
    }

    @Test
    public void testRoundTripsPolicyExceptionsAndCondition() {
        RetryPolicy policy = RetryPolicy.builder()
                .maxRetries(4)
                .foregroundMaxRetries(1)
                .backoff(Backoffs.increment(4L, 6L))
                .retryOn(IllegalStateException.class)
                .abortOn(InterruptedException.class)
                .condition("message != 'stop'")
                .build();
        RetryPolicy restored = roundTrip(record(policy)).getRequest().getPolicy();

        Assert.assertEquals(4, restored.getMaxRetries());
        Assert.assertEquals(Integer.valueOf(1), restored.getForegroundMaxRetries());
        Assert.assertEquals(4L, restored.getDelayMillis(1));
        Assert.assertEquals(10L, restored.getDelayMillis(2));
        Assert.assertTrue(restored.getRetryOnExceptions().contains(IllegalStateException.class));
        Assert.assertTrue(restored.getAbortOnExceptions().contains(InterruptedException.class));
        Assert.assertEquals("message != 'stop'", restored.getCondition());
    }

    @Test
    public void testRejectsUnknownSchemaVersion() {
        assertRejected("{\"version\":0}");
        assertRejected("{\"version\":2}");
    }

    @Test
    public void testRejectsNonAllowlistedThrowableClassName() {
        // java.* 始终放行，但未知类名与 allowlist 外的业务类必须被拒绝
        assertRejected(retryOnPayload("no.such.NotFound"));
        assertRejected(retryOnPayload(CustomThrowable.class.getName()));
    }

    @Test
    public void testExplicitAllowlistRoundTripsCustomThrowable() {
        Set<Class<? extends Throwable>> allowlist =
                new LinkedHashSet<Class<? extends Throwable>>();
        allowlist.add(CustomThrowable.class);
        VersionedRetryRecordSerializer explicit =
                new VersionedRetryRecordSerializer(BackoffRegistry.global(), allowlist);

        RetryPolicy policy = RetryPolicy.builder()
                .maxRetries(2)
                .foregroundMaxRetries(0)
                .backoff(Backoffs.fixed(12L))
                .retryOn(CustomThrowable.class)
                .build();
        RetryRecord restored = explicit.deserialize(explicit.serialize(record(policy)));

        Assert.assertTrue(restored.getRequest().getPolicy().getRetryOnExceptions()
                .contains(CustomThrowable.class));
    }

    @Test
    public void testRejectsTaskIdInconsistencyAndMissingCreatedAt() {
        assertRejected("{\"version\":1,\"taskId\":\"task-root\",\"request\":{\"taskId\":\"task-req\","
                + "\"taskType\":\"x\",\"recovery\":{\"taskType\":\"x\"},"
                + "\"createdAt\":\"2024-01-01T00:00:00Z\","
                + "\"policy\":{\"backoff\":{\"type\":\"fixed\",\"params\":{\"delay\":1}}}},"
                + "\"state\":{\"attempts\":0,\"status\":\"ACCEPTED\"}}");
        assertRejected("{\"version\":1,\"request\":{\"taskType\":\"x\",\"recovery\":{\"taskType\":\"x\"},"
                + "\"policy\":{\"backoff\":{\"type\":\"fixed\",\"params\":{\"delay\":1}}}},"
                + "\"state\":{\"attempts\":0,\"status\":\"ACCEPTED\"}}");
    }

    @Test
    public void testRejectsTerminalStateWithoutRequiredTimestamp() {
        assertRejected(terminalState("SUCCEEDED", false));
        assertRejected(terminalState("FAILED", false));
        assertRejected(terminalState("CANCELLED", false));
    }

    @Test
    public void testDeprecatedJsonSerializerDelegatesToVersionedImpl() {
        RetryRecord record = record(policy(Backoffs.fixed(5L)));
        Assert.assertEquals(
                VersionedRetryRecordSerializer.INSTANCE.serialize(record),
                JsonRetryRecordSerializer.INSTANCE.serialize(record));
    }

    private static void assertBackoff(Backoff backoff, long first, long second) {
        RetryPolicy restored = roundTrip(record(policy(backoff))).getRequest().getPolicy();
        Assert.assertEquals(backoff.getClass(), restored.getBackoff().getClass());
        Assert.assertEquals(first, restored.getBackoff().calculateMillis(1));
        Assert.assertEquals(second, restored.getBackoff().calculateMillis(2));
    }

    private static RetryRecord roundTrip(RetryRecord record) {
        return new VersionedRetryRecordSerializer()
                .deserialize(new VersionedRetryRecordSerializer().serialize(record));
    }

    private static RetryRecord record(RetryPolicy policy) {
        return RetryRecord.builder()
                .request(RetryRequest.builder()
                        .taskType("payment")
                        .idempotencyKey("serializer-test")
                        .recovery(RecoverySpec.of("payment", "payload-1"))
                        .policy(policy)
                        .createdAt(Instant.now())
                        .build())
                .state(RetryState.builder()
                        .attempts(0)
                        .status(RetryStatus.ACCEPTED)
                        .nextRunAt(Instant.now())
                        .build())
                .build();
    }

    private static RetryPolicy policy(Backoff backoff) {
        return RetryPolicy.builder()
                .maxRetries(2)
                .foregroundMaxRetries(0)
                .backoff(backoff)
                .build();
    }

    private static String retryOnPayload(String className) {
        return "{\"version\":1,\"request\":{\"taskType\":\"x\",\"recovery\":{\"taskType\":\"x\"},"
                + "\"policy\":{\"backoff\":{\"type\":\"fixed\",\"params\":{}},"
                + "\"retryOn\":[\"" + className + "\"]}},\"state\":{\"attempts\":0,\"status\":\"ACCEPTED\"}}";
    }

    private static String terminalState(String status, boolean withTimestamp) {
        return "{\"version\":1,\"request\":{\"taskType\":\"x\","
                + "\"recovery\":{\"taskType\":\"x\"},\"createdAt\":\"2024-01-01T00:00:00Z\","
                + "\"policy\":{\"backoff\":{\"type\":\"fixed\",\"params\":{\"delay\":1}}}},"
                + "\"state\":{\"attempts\":0,\"status\":\"" + status + "\","
                + (withTimestamp ? "\"succeededAt\":\"2024-01-01T00:00:01Z\"," : "")
                + "\"backendTaskId\":null}}";
    }

    private static void assertRejected(String payload) {
        try {
            new VersionedRetryRecordSerializer().deserialize(payload);
            Assert.fail("Expected malformed payload rejection");
        } catch (IllegalArgumentException | IllegalStateException expected) {
            Assert.assertNotNull(expected.getMessage());
        }
    }

    public static final class CustomThrowable extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
