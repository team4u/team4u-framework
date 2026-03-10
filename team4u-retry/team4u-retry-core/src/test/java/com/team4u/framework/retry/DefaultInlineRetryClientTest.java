package com.team4u.framework.retry;

import com.team4u.framework.retry.backoff.Backoffs;
import com.team4u.framework.retry.client.DefaultInlineRetryClient;
import com.team4u.framework.retry.policy.RetryPolicy;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class DefaultInlineRetryClientTest {

    @Test
    public void testExecuteDoesNotRetryError() {
        AtomicInteger attempts = new AtomicInteger();

        try {
            DefaultInlineRetryClient.getInstance().execute(retryPolicy(), () -> {
                attempts.incrementAndGet();
                throw new OutOfMemoryError("oom");
            });
            Assert.fail("expected OutOfMemoryError");
        } catch (OutOfMemoryError error) {
            Assert.assertEquals("oom", error.getMessage());
        } catch (Exception ex) {
            Assert.fail("expected Error, but got " + ex.getClass().getName());
        }

        Assert.assertEquals(1, attempts.get());
    }

    @Test
    public void testExecuteAsyncDoesNotRetryError() {
        AtomicInteger attempts = new AtomicInteger();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        try {
            CompletableFuture<String> future = DefaultInlineRetryClient.getInstance().executeAsync(
                    retryPolicy(),
                    () -> {
                        attempts.incrementAndGet();
                        throw new OutOfMemoryError("oom");
                    },
                    scheduler);

            try {
                future.join();
                Assert.fail("expected CompletionException");
            } catch (CompletionException ex) {
                Assert.assertTrue(ex.getCause() instanceof OutOfMemoryError);
                Assert.assertEquals("oom", ex.getCause().getMessage());
            }
        } finally {
            scheduler.shutdownNow();
        }

        Assert.assertEquals(1, attempts.get());
    }

    @Test
    public void testExecuteAsyncCancellationCancelsInFlightFuture() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger attempts = new AtomicInteger();
        TrackingCompletableFuture<String> childFuture = new TrackingCompletableFuture<String>();

        try {
            CompletableFuture<String> resultFuture = DefaultInlineRetryClient.getInstance().executeAsync(
                    retryPolicy(),
                    () -> {
                        attempts.incrementAndGet();
                        return childFuture;
                    },
                    scheduler);

            Assert.assertTrue(resultFuture.cancel(true));
            Thread.sleep(50L);

            Assert.assertTrue(resultFuture.isCancelled());
            Assert.assertEquals(1, attempts.get());
            Assert.assertTrue(childFuture.cancelCalled.get());
            Assert.assertTrue(childFuture.isCancelled());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    public void testExecuteAsyncCancellationSkipsScheduledRetry() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger attempts = new AtomicInteger();
        RetryPolicy policy = RetryPolicy.builder()
                .maxRetries(2)
                .backoff(Backoffs.fixed(200))
                .build();

        try {
            CompletableFuture<String> resultFuture = DefaultInlineRetryClient.getInstance().executeAsync(
                    policy,
                    () -> {
                        attempts.incrementAndGet();
                        CompletableFuture<String> failed = new CompletableFuture<String>();
                        failed.completeExceptionally(new RuntimeException("fail"));
                        return failed;
                    },
                    scheduler);

            Assert.assertTrue(resultFuture.cancel(true));
            Thread.sleep(350L);

            Assert.assertTrue(resultFuture.isCancelled());
            Assert.assertEquals(1, attempts.get());
        } finally {
            scheduler.shutdownNow();
        }
    }

    private RetryPolicy retryPolicy() {
        return RetryPolicy.builder()
                .maxRetries(2)
                .backoff(Backoffs.fixed(0))
                .build();
    }

    private static class TrackingCompletableFuture<T> extends CompletableFuture<T> {
        private final AtomicBoolean cancelCalled = new AtomicBoolean();

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelCalled.set(true);
            return super.cancel(mayInterruptIfRunning);
        }
    }
}
