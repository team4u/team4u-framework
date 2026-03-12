package com.team4u.framework.retry;

import com.team4u.framework.retry.backoff.Backoffs;
import com.team4u.framework.retry.client.ManagedRetryClient;
import com.team4u.framework.retry.domain.ManagedSubmitResult;
import com.team4u.framework.retry.domain.RetryTaskSpec;
import com.team4u.framework.retry.domain.store.RetryStatus;
import com.team4u.framework.retry.policy.RetryPolicy;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class RetriesTest {

    @Test
    public void testManagedDslBuildsSpecFromPolicy() {
        RecordingManagedRetryClient client = new RecordingManagedRetryClient();
        RetryPolicy policy = RetryPolicy.builder()
                .maxRetries(4)
                .foregroundMaxRetries(1)
                .backoff(Backoffs.fixed(50L))
                .build();

        RetryTaskSpec<String> spec = Retries.managed(client)
                .task("pay-notify")
                .idempotentBy("order-1001")
                .payload("payload")
                .policy(policy)
                .toSpec(() -> "ok");

        Assert.assertEquals("order-1001", spec.getIdempotencyKey());
        Assert.assertEquals("pay-notify", spec.getRecovery().getTaskType());
        Assert.assertEquals("payload", spec.getRecovery().getPayload());
        Assert.assertEquals(4, spec.getPolicy().getMaxRetries());
        Assert.assertEquals(Integer.valueOf(1), spec.getPolicy().getForegroundMaxRetries());
        Assert.assertEquals(50L, spec.getPolicy().getDelayMillis(1));
    }

    @Test
    public void testManagedCallReturnsManagedSubmitResultDirectly() {
        RecordingManagedRetryClient client = new RecordingManagedRetryClient();
        ManagedSubmitResult.Completed<String> expected = new ManagedSubmitResult.Completed<String>("done");
        client.result = expected;

        ManagedSubmitResult<String> actual = Retries.managed(client)
                .task("pay-notify")
                .idempotentBy("order-1002")
                .payload("payload")
                .policy(RetryPolicy.builder()
                        .maxRetries(2)
                        .foregroundMaxRetries(0)
                        .backoff(Backoffs.fixed(0L))
                        .build())
                .call(() -> "done");

        Assert.assertSame(expected, actual);
        Assert.assertNotNull(client.lastSpec);
        Assert.assertEquals("pay-notify", client.lastSpec.getRecovery().getTaskType());
    }

    @Test
    public void testManagedDslRejectsPolicyWithoutForegroundMaxRetries() {
        RecordingManagedRetryClient client = new RecordingManagedRetryClient();
        try {
            Retries.managed(client)
                    .task("pay-notify")
                    .idempotentBy("order-1003")
                    .policy(RetryPolicy.builder()
                            .maxRetries(2)
                            .backoff(Backoffs.fixed(0L))
                            .build())
                    .call(() -> "ignored");
            Assert.fail("expected IllegalStateException");
        } catch (IllegalStateException ex) {
            Assert.assertTrue(ex.getMessage().contains("foregroundMaxRetries"));
        }

        Assert.assertNull(client.lastSpec);
    }

    @Test
    public void testManagedDslRejectsMissingPolicy() {
        RecordingManagedRetryClient client = new RecordingManagedRetryClient();

        try {
            Retries.managed(client)
                    .task("pay-notify")
                    .idempotentBy("order-1004")
                    .payload("payload")
                    .call(() -> "done");
            Assert.fail("expected IllegalStateException");
        } catch (IllegalStateException ex) {
            Assert.assertTrue(ex.getMessage().contains("RetryPolicy"));
        }

        Assert.assertNull(client.lastSpec);
    }

    @Test
    public void testInlineAsyncDelegatesWithProvidedScheduler() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            CompletableFuture<String> future = Retries.inline()
                    .policy(RetryPolicy.builder()
                            .maxRetries(0)
                            .backoff(Backoffs.fixed(0L))
                            .build())
                    .callAsync(() -> CompletableFuture.completedFuture("ok"), scheduler);

            Assert.assertEquals("ok", future.get(1, TimeUnit.SECONDS));
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    public void testInlineDefaultPolicyRetriesUsingDefaultMaxRetries() {
        AtomicInteger attempts = new AtomicInteger();

        try {
            Retries.inline()
                    .policy(RetryPolicy.builder().build())
                    .call(() -> {
                        attempts.incrementAndGet();
                        throw new IllegalStateException("boom");
                    });
            Assert.fail("expected IllegalStateException");
        } catch (IllegalStateException ex) {
            Assert.assertEquals("boom", ex.getMessage());
        } catch (Exception ex) {
            Assert.fail("expected IllegalStateException, but got " + ex.getClass().getName());
        }

        Assert.assertEquals(3, attempts.get());
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
