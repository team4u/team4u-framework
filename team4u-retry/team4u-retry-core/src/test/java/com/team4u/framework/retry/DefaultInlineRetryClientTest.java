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

    private RetryPolicy retryPolicy() {
        return RetryPolicy.builder()
                .maxAttempts(3)
                .backoff(Backoffs.fixed(0))
                .build();
    }
}
