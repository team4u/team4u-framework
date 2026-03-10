package com.team4u.framework.retry;

import com.team4u.framework.retry.backoff.Backoffs;
import com.team4u.framework.retry.client.ManagedRetryClient;
import com.team4u.framework.retry.domain.ManagedSubmitResult;
import com.team4u.framework.retry.domain.RetryTaskSpec;
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
                .foregroundMaxAttempts(2)
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
        Assert.assertEquals(5, spec.getPolicy().getMaxAttempts());
        Assert.assertEquals(Integer.valueOf(2), spec.getPolicy().getForegroundAttempts());
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
                        .foregroundMaxAttempts(1)
                        .backoff(Backoffs.fixed(0L))
                        .build())
                .call(() -> "done");

        Assert.assertSame(expected, actual);
        Assert.assertNotNull(client.lastSpec);
        Assert.assertEquals("pay-notify", client.lastSpec.getRecovery().getTaskType());
    }

    @Test
    public void testManagedDslRequiresForegroundAttempts() {
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
            Assert.assertTrue(ex.getMessage().contains("foregroundMaxAttempts"));
        }

        Assert.assertNull(client.lastSpec);
    }

    @Test
    public void testInlineAsyncDelegatesWithProvidedScheduler() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            AtomicInteger attempts = new AtomicInteger();

            CompletableFuture<String> future = Retries.inline()
                    .policy(RetryPolicy.builder()
                            .maxRetries(1)
                            .backoff(Backoffs.fixed(0L))
                            .retryOn(IllegalStateException.class)
                            .build())
                    .callAsync(() -> {
                        if (attempts.getAndIncrement() == 0) {
                            CompletableFuture<String> failed = new CompletableFuture<String>();
                            failed.completeExceptionally(new IllegalStateException("boom"));
                            return failed;
                        }
                        return CompletableFuture.completedFuture("ok");
                    }, scheduler);

            Assert.assertEquals("ok", future.get(1, TimeUnit.SECONDS));
            Assert.assertEquals(2, attempts.get());
        } finally {
            scheduler.shutdownNow();
        }
    }

    private static class RecordingManagedRetryClient implements ManagedRetryClient {
        private RetryTaskSpec<?> lastSpec;
        private ManagedSubmitResult<?> result = new ManagedSubmitResult.Accepted<Object>("task-1", "SCHEDULED", null);

        @SuppressWarnings("unchecked")
        @Override
        public <T> ManagedSubmitResult<T> submit(RetryTaskSpec<T> spec) {
            this.lastSpec = spec;
            return (ManagedSubmitResult<T>) result;
        }
    }
}
