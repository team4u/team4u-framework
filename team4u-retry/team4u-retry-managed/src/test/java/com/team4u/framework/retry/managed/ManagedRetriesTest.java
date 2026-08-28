package com.team4u.framework.retry.managed;

import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.common.backoff.Backoffs;
import com.team4u.framework.retry.managed.client.ManagedRetryClient;
import com.team4u.framework.retry.managed.model.RetryStatus;
import com.team4u.framework.retry.managed.submit.RetryTaskSpec;
import org.junit.Assert;
import org.junit.Test;

public class ManagedRetriesTest {

    @Test
    public void managedExecutionIsPublicFluentType() {
        Assert.assertNotNull(ManagedRetries.ManagedExecution.class);
    }
    @Test
    public void withRejectsNullClient() {
        try {
            ManagedRetries.with(null);
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            Assert.assertEquals("ManagedRetryClient must not be null", ex.getMessage());
        }
    }

    @Test
    public void managedExecutionReturnsItselfFromEveryConfigurationMethod() {
        ManagedRetries.ManagedExecution execution = ManagedRetries.with(new RecordingManagedRetryClient());

        Assert.assertSame(execution, execution.taskType("pay-notify"));
        Assert.assertSame(execution, execution.idempotencyKey("order-1001"));
        Assert.assertSame(execution, execution.payload("payload"));
        Assert.assertSame(execution, execution.policy(policy(1)));
    }

    @Test
    public void managedDslBuildsSpecFromPolicy() {
        RecordingManagedRetryClient client = new RecordingManagedRetryClient();
        RetryPolicy retryPolicy = policy(1);

        RetryTaskSpec<String> spec = ManagedRetries.with(client)
                .taskType("pay-notify")
                .idempotencyKey("order-1001")
                .payload("payload")
                .policy(retryPolicy)
                .toSpec(() -> "ok");

        Assert.assertEquals("order-1001", spec.getIdempotencyKey());
        Assert.assertEquals("pay-notify", spec.getRecovery().getTaskType());
        Assert.assertEquals("payload", spec.getRecovery().getPayload());
        Assert.assertSame(retryPolicy, spec.getPolicy());
        Assert.assertNull(client.lastSpec);
    }

    @Test
    public void managedCallReturnsManagedSubmitResultDirectly() {
        RecordingManagedRetryClient client = new RecordingManagedRetryClient();
        ManagedSubmitResult.Completed<String> expected = new ManagedSubmitResult.Completed<String>("done");
        client.result = expected;

        ManagedSubmitResult<String> actual = ManagedRetries.with(client)
                .taskType("pay-notify")
                .idempotencyKey("order-1002")
                .payload("payload")
                .policy(policy(0))
                .call(() -> "done");

        Assert.assertSame(expected, actual);
        Assert.assertEquals("pay-notify", client.lastSpec.getRecovery().getTaskType());
    }

    @Test
    public void managedDslRejectsBlankTaskType() {
        RecordingManagedRetryClient client = new RecordingManagedRetryClient();
        try {
            ManagedRetries.with(client)
                    .idempotencyKey("order-1003")
                    .policy(policy(0))
                    .call(() -> "done");
            Assert.fail("expected IllegalStateException");
        } catch (IllegalStateException ex) {
            Assert.assertEquals("Managed taskType must not be blank", ex.getMessage());
        }
        Assert.assertNull(client.lastSpec);
    }

    @Test
    public void managedDslRejectsBlankIdempotencyKey() {
        RecordingManagedRetryClient client = new RecordingManagedRetryClient();
        try {
            ManagedRetries.with(client)
                    .taskType("pay-notify")
                    .policy(policy(0))
                    .call(() -> "done");
            Assert.fail("expected IllegalStateException");
        } catch (IllegalStateException ex) {
            Assert.assertEquals("Managed idempotencyKey must not be blank", ex.getMessage());
        }
        Assert.assertNull(client.lastSpec);
    }

    @Test
    public void managedDslRejectsMissingPolicy() {
        RecordingManagedRetryClient client = new RecordingManagedRetryClient();
        try {
            ManagedRetries.with(client)
                    .taskType("pay-notify")
                    .idempotencyKey("order-1004")
                    .payload("payload")
                    .call(() -> "done");
            Assert.fail("expected IllegalStateException");
        } catch (IllegalStateException ex) {
            Assert.assertEquals("Managed RetryPolicy must be configured before calling task", ex.getMessage());
        }
        Assert.assertNull(client.lastSpec);
    }

    @Test
    public void managedDslRejectsPolicyWithoutForegroundMaxRetries() {
        RecordingManagedRetryClient client = new RecordingManagedRetryClient();
        try {
            ManagedRetries.with(client)
                    .taskType("pay-notify")
                    .idempotencyKey("order-1005")
                    .policy(RetryPolicy.builder().maxRetries(2).backoff(Backoffs.fixed(0L)).build())
                    .call(() -> "ignored");
            Assert.fail("expected IllegalStateException");
        } catch (IllegalStateException ex) {
            Assert.assertEquals(
                    "Managed RetryPolicy must configure foregroundMaxRetries before calling task",
                    ex.getMessage());
        }
        Assert.assertNull(client.lastSpec);
    }

    @Test
    public void managedDslRejectsNullTask() {
        RecordingManagedRetryClient client = new RecordingManagedRetryClient();
        try {
            ManagedRetries.with(client)
                    .taskType("pay-notify")
                    .idempotencyKey("order-1006")
                    .policy(policy(0))
                    .call(null);
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            Assert.assertEquals("Task must not be null", ex.getMessage());
        }
        Assert.assertNull(client.lastSpec);
    }

    private static RetryPolicy policy(int foregroundMaxRetries) {
        return RetryPolicy.builder()
                .maxRetries(2)
                .foregroundMaxRetries(foregroundMaxRetries)
                .backoff(Backoffs.fixed(0L))
                .build();
    }

    private static class RecordingManagedRetryClient implements ManagedRetryClient {
        private RetryTaskSpec<?> lastSpec;
        private ManagedSubmitResult<?> result =
                new ManagedSubmitResult.Accepted<Object>("task-1", RetryStatus.WAITING_RETRY, null);

        @SuppressWarnings("unchecked")
        @Override
        public <T> ManagedSubmitResult<T> submit(RetryTaskSpec<T> spec) {
            this.lastSpec = spec;
            return (ManagedSubmitResult<T>) result;
        }
    }
}
